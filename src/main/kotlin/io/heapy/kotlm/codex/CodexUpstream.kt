package io.heapy.kotlm.codex

import io.heapy.kotlm.json
import io.heapy.kotlm.with
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private object UnauthorizedUpstream : RuntimeException(null, null, false, false)

/**
 * Calls Codex on behalf of the subscription. Every upstream request uses stream=true,
 * the only response form supported by the backend. Converting that stream to regular
 * JSON is simpler than trying to make the backend return a complete response.
 */
class CodexUpstream(
    private val httpClient: HttpClient,
    private val tokenManager: TokenManager,
) {
    suspend fun <T> stream(
        payload: JsonObject,
        requestId: String,
        consumer: suspend (HttpResponse) -> T,
    ): T = try {
        attempt(payload, requestId, forceRefresh = false, consumer = consumer)
    } catch (_: UnauthorizedUpstream) {
        // The token may expire between the exp check and the request, so refresh and retry once.
        attempt(payload, requestId, forceRefresh = true, consumer = consumer)
    }

    private suspend fun <T> attempt(
        payload: JsonObject,
        requestId: String,
        forceRefresh: Boolean,
        consumer: suspend (HttpResponse) -> T,
    ): T {
        val credentials = tokenManager.credentials(forceRefresh = forceRefresh)
        val body = payload.with(
            "stream" to JsonPrimitive(true),
            // The subscription caches the shared request prefix under this key.
            "prompt_cache_key" to JsonPrimitive(requestId),
        )

        return httpClient.preparePost("${credentials.baseUrl}/responses") {
            contentType(ContentType.Application.Json)
            headers {
                append("Authorization", "Bearer ${credentials.accessToken}")
                append("Accept", "text/event-stream")
                append("User-Agent", "codex_cli_rs/0.0.0 (Heapy kotlm)")
                append("originator", "codex_cli_rs")
                credentials.accountId?.let { append("ChatGPT-Account-ID", it) }
                append("session_id", requestId)
                append("x-client-request-id", requestId)
            }
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }.execute { response ->
            if (response.status.value == 401 && !forceRefresh) throw UnauthorizedUpstream
            consumer(response)
        }
    }
}
