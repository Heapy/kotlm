package io.heapy.kotlm.codex

import io.heapy.kotlm.accountIdFromToken
import io.heapy.kotlm.int
import io.heapy.kotlm.json
import io.heapy.kotlm.string
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

data class DeviceChallenge(
    val id: String,
    val deviceAuthId: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val createdAt: Long,
)

sealed interface DevicePollResult {
    data class Pending(val retryAfterSeconds: Int) : DevicePollResult
    data object Complete : DevicePollResult
}

/**
 * Logs in to the subscription directly from the server. The response displays a code
 * that can be confirmed in a browser on any device, avoiding manual file copies on
 * headless machines.
 */
class DeviceLogin(
    private val httpClient: HttpClient,
    private val tokenManager: TokenManager,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "") },
) {
    private val challenges = ConcurrentHashMap<String, DeviceChallenge>()

    suspend fun start(): DeviceChallenge {
        val response = httpClient.post("$CODEX_OAUTH_ISSUER/api/accounts/deviceauth/usercode") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("client_id", CODEX_OAUTH_CLIENT_ID) }))
        }
        if (response.status.value != 200) {
            throw CodexAuthException(
                "Device login could not start: HTTP ${response.status.value}",
                code = "device_code_request_failed",
            )
        }

        val payload = jsonObjectOf(response.bodyAsText(), "device_code_incomplete")
        val userCode = payload.string("user_code")
        val deviceAuthId = payload.string("device_auth_id")
        if (userCode.isNullOrBlank() || deviceAuthId.isNullOrBlank()) {
            throw CodexAuthException("Device login response has no code", code = "device_code_incomplete")
        }

        val challenge = DeviceChallenge(
            id = newId(),
            deviceAuthId = deviceAuthId,
            userCode = userCode,
            verificationUrl = "$CODEX_OAUTH_ISSUER/codex/device",
            intervalSeconds = (payload.int("interval") ?: 5).coerceAtLeast(3),
            createdAt = now(),
        )
        challenges[challenge.id] = challenge
        return challenge
    }

    suspend fun poll(challengeId: String): DevicePollResult {
        val challenge = challenges[challengeId]
            ?: throw CodexAuthException("Unknown device login", code = "device_code_unknown")
        if (now() - challenge.createdAt > 15 * 60) {
            challenges.remove(challengeId)
            throw CodexAuthException("Device login expired, start again", code = "device_code_timeout")
        }

        val response = httpClient.post("$CODEX_OAUTH_ISSUER/api/accounts/deviceauth/token") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("device_auth_id", challenge.deviceAuthId)
                        put("user_code", challenge.userCode)
                    },
                ),
            )
        }
        // The server responds with 403 or 404 until the subscription owner confirms the code.
        if (response.status.value == 403 || response.status.value == 404) {
            return DevicePollResult.Pending(challenge.intervalSeconds)
        }
        if (response.status.value != 200) {
            throw CodexAuthException(
                "Device login check failed: HTTP ${response.status.value}",
                code = "device_code_poll_failed",
            )
        }

        val payload = jsonObjectOf(response.bodyAsText(), "device_code_incomplete")
        val authorizationCode = payload.string("authorization_code")
        val codeVerifier = payload.string("code_verifier")
        if (authorizationCode.isNullOrBlank() || codeVerifier.isNullOrBlank()) {
            throw CodexAuthException("Device login response is incomplete", code = "device_code_incomplete")
        }

        tokenManager.store(exchange(authorizationCode, codeVerifier))
        challenges.remove(challengeId)
        return DevicePollResult.Complete
    }

    private suspend fun exchange(authorizationCode: String, codeVerifier: String): CodexCredentials {
        val response = httpClient.submitForm(
            url = CODEX_OAUTH_TOKEN_URL,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", authorizationCode)
                append("redirect_uri", "$CODEX_OAUTH_ISSUER/deviceauth/callback")
                append("client_id", CODEX_OAUTH_CLIENT_ID)
                append("code_verifier", codeVerifier)
            },
        )
        if (response.status.value != 200) {
            throw CodexAuthException(
                "Token exchange failed: HTTP ${response.status.value}",
                code = "token_exchange_failed",
            )
        }

        val payload = jsonObjectOf(response.bodyAsText(), "token_exchange_incomplete")
        val access = payload.string("access_token")
        val refresh = payload.string("refresh_token")
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            throw CodexAuthException("Token exchange returned no tokens", code = "token_exchange_incomplete")
        }
        return CodexCredentials(
            accessToken = access,
            refreshToken = refresh,
            accountId = accountIdFromToken(access),
        )
    }

    private fun jsonObjectOf(body: String, errorCode: String): JsonObject =
        runCatching { json.parseToJsonElement(body) as JsonObject }
            .getOrElse { throw CodexAuthException("Device login returned no JSON", code = errorCode) }
}
