package io.heapy.kotlm.codex

import io.heapy.kotlm.accountIdFromToken
import io.heapy.kotlm.json
import io.heapy.kotlm.string
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(TokenManager::class.java)

data class AuthStatus(
    val authenticated: Boolean,
    val expiresInSeconds: Long?,
    val accountId: String?,
    val error: String? = null,
)

/**
 * The only component that accesses Codex tokens. Reading, expiration checks, refreshes,
 * and writes share one lock so two concurrent requests cannot use the same refresh token
 * and invalidate one another.
 */
class TokenManager(
    private val authFile: Path,
    private val stateFile: Path,
    private val httpClient: HttpClient,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val onPersistenceFailure: (Throwable) -> Unit = {},
) {
    private val mutex = Mutex()
    private var cached: CodexCredentials? = null
    private var pendingPersistence: CodexCredentials? = null
    private var persistenceError: Throwable? = null

    suspend fun credentials(forceRefresh: Boolean = false): CodexCredentials = mutex.withLock {
        val current = cached ?: readCredentials(stateFile, authFile)
        cached = current
        pendingPersistence?.let(::persist)

        val fresh = if (forceRefresh || credentialsExpiring(current, now())) {
            refresh(current).also {
                cached = it
                pendingPersistence = it
                persist(it)
            }
        } else {
            current
        }
        cached = fresh
        fresh
    }

    suspend fun store(credentials: CodexCredentials): Boolean = mutex.withLock {
        cached = credentials
        pendingPersistence = credentials
        persist(credentials)
    }

    suspend fun status(): AuthStatus = mutex.withLock {
        try {
            val credentials = cached ?: readCredentials(stateFile, authFile).also { cached = it }
            pendingPersistence?.let(::persist)
            AuthStatus(
                authenticated = true,
                expiresInSeconds = credentials.expiresAt()?.let { it - now() },
                accountId = credentials.accountId,
                error = persistenceError?.let { "auth_state_not_persisted" },
            )
        } catch (error: Exception) {
            AuthStatus(
                authenticated = false,
                expiresInSeconds = null,
                accountId = null,
                error = (error as? CodexAuthException)?.code ?: "auth_unavailable",
            )
        }
    }

    private fun persist(credentials: CodexCredentials): Boolean = try {
        writeCredentials(stateFile, credentials)
        if (persistenceError != null) log.info("Codex auth state is writable again: {}", stateFile)
        pendingPersistence = null
        persistenceError = null
        true
    } catch (error: Exception) {
        pendingPersistence = credentials
        if (persistenceError == null) {
            log.error("Could not persist Codex auth state to {}", stateFile, error)
            onPersistenceFailure(error)
        }
        persistenceError = error
        false
    }

    private suspend fun refresh(current: CodexCredentials): CodexCredentials {
        val response = httpClient.submitForm(
            url = CODEX_OAUTH_TOKEN_URL,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", current.refreshToken)
                append("client_id", CODEX_OAUTH_CLIENT_ID)
            },
        )

        if (response.status == HttpStatusCode.TooManyRequests) {
            throw CodexAuthException("Codex subscription quota is exhausted", code = "codex_rate_limited")
        }
        if (response.status.value !in 200..299) {
            throw CodexAuthException(
                "Codex OAuth refresh failed with HTTP ${response.status.value}",
                code = "codex_refresh_failed",
                reloginRequired = response.status.value in setOf(400, 401, 403),
            )
        }

        val payload = runCatching { json.parseToJsonElement(response.bodyAsText()) as JsonObject }
            .getOrElse { throw CodexAuthException("Codex OAuth refresh returned no JSON", code = "refresh_invalid") }
        val access = payload.string("access_token")
            ?: throw CodexAuthException("Codex OAuth refresh returned no access token", code = "refresh_incomplete")

        return CodexCredentials(
            accessToken = access,
            refreshToken = payload.string("refresh_token") ?: current.refreshToken,
            baseUrl = current.baseUrl,
            accountId = accountIdFromToken(access) ?: current.accountId,
        )
    }
}
