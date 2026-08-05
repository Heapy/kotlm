package io.heapy.kotlm.codex

import io.heapy.kotlm.accountIdFromToken
import io.heapy.kotlm.json
import io.heapy.kotlm.obj
import io.heapy.kotlm.replaceStateFile
import io.heapy.kotlm.string
import io.heapy.kotlm.tokenExpiresAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists
import kotlin.io.path.readText

const val CODEX_BASE_URL: String = "https://chatgpt.com/backend-api/codex"
const val CODEX_OAUTH_ISSUER: String = "https://auth.openai.com"
const val CODEX_OAUTH_TOKEN_URL: String = "$CODEX_OAUTH_ISSUER/oauth/token"
const val CODEX_OAUTH_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
const val CODEX_REFRESH_SKEW_SECONDS: Long = 120

class CodexAuthException(
    message: String,
    val code: String,
    val reloginRequired: Boolean = false,
) : RuntimeException(message)

data class CodexCredentials(
    val accessToken: String,
    val refreshToken: String,
    val baseUrl: String = CODEX_BASE_URL,
    val accountId: String? = accountIdFromToken(accessToken),
) {
    fun expiresAt(): Long? = tokenExpiresAt(accessToken)
}

/**
 * Accepts both the Codex CLI form and the canonical provider form wrapped in providers.
 * The file itself is never exposed; only status information leaves the service.
 */
fun parseAuthDocument(document: JsonObject): CodexCredentials {
    val provider = document.obj("providers")?.obj("openai-codex")
    val tokens = provider?.obj("tokens")
        ?: document.obj("tokens")
        ?: document
    val baseUrl = provider?.string("base_url") ?: document.string("base_url") ?: CODEX_BASE_URL

    val access = tokens.string("access_token") ?: tokens.string("accessToken")
    val refresh = tokens.string("refresh_token") ?: tokens.string("refreshToken")
    if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
        throw CodexAuthException(
            "Codex auth document has no access or refresh token",
            code = "auth_tokens_missing",
            reloginRequired = true,
        )
    }

    return CodexCredentials(
        accessToken = access,
        refreshToken = refresh,
        baseUrl = baseUrl.trimEnd('/'),
        accountId = tokens.string("account_id") ?: accountIdFromToken(access),
    )
}

fun readCredentials(stateFile: Path, authFile: Path): CodexCredentials {
    val source = if (stateFile.exists()) stateFile else authFile
    if (!source.exists()) {
        throw CodexAuthException(
            "Codex auth file is missing: $source",
            code = "auth_file_missing",
            reloginRequired = true,
        )
    }
    val document = runCatching { json.parseToJsonElement(source.readText()) as JsonObject }
        .getOrElse {
            throw CodexAuthException(
                "Codex auth file is not a JSON object: $source",
                code = "auth_document_invalid",
                reloginRequired = true,
            )
        }
    return parseAuthDocument(document)
}

/**
 * Refresh tokens rotate, so the new value must survive a restart. Write to a temporary
 * file and rename it atomically because a partially written state would require a new login.
 */
fun writeCredentials(stateFile: Path, credentials: CodexCredentials) {
    val document = buildJsonObject {
        put("version", 1)
        put("base_url", credentials.baseUrl)
        put(
            "tokens",
            buildJsonObject {
                put("access_token", credentials.accessToken)
                put("refresh_token", credentials.refreshToken)
                credentials.accountId?.let { put("account_id", it) }
            },
        )
    }

    replaceStateFile(
        file = stateFile,
        contents = json.encodeToString(JsonObject.serializer(), document),
    ) { temporary ->
        try {
            Files.setPosixFilePermissions(
                temporary,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems do not expose these permissions.
        }
    }
}

fun credentialsExpiring(credentials: CodexCredentials, now: Long): Boolean {
    val expiresAt = credentials.expiresAt() ?: return false
    return expiresAt <= now + CODEX_REFRESH_SKEW_SECONDS
}
