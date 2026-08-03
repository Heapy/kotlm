package io.heapy.kotlm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * A project allowed to use the proxy. Project keys are separate from the subscription,
 * so one project can be revoked without affecting the others or requiring another login.
 */
@Serializable
data class ClientConfig(
    val name: String,
    val key: String,
    @SerialName("requestsPerMinute")
    val requestsPerMinute: Int = 60,
    /** Daily token ceiling; 0 means unlimited. */
    @SerialName("dailyTokens")
    val dailyTokens: Long = 0,
)

@Serializable
private data class ClientsDocument(val clients: List<ClientConfig> = emptyList())

data class KotlmConfig(
    val host: String,
    val port: Int,
    /** Secret file containing Codex tokens, used only for initial bootstrapping. */
    val authFile: Path,
    /** Writable state that receives the rotated refresh token. */
    val stateFile: Path,
    val usageFile: Path,
    val clients: List<ClientConfig>,
    val adminKey: String?,
    val allowedModels: List<String>,
    val maxOutputTokens: Int,
    val upstreamTimeoutSeconds: Long,
) {
    val clientsByKey: Map<String, ClientConfig> = clients.associateBy(ClientConfig::key)
}

class ConfigurationException(message: String) : RuntimeException(message)

private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)

private fun intEnv(name: String, fallback: Int, minimum: Int, maximum: Int): Int {
    val raw = env(name) ?: return fallback
    val value = raw.toIntOrNull()
        ?: throw ConfigurationException("$name must be an integer, got \"$raw\"")
    if (value < minimum || value > maximum) {
        throw ConfigurationException("$name must be between $minimum and $maximum, got $value")
    }
    return value
}

private fun readClients(): List<ClientConfig> {
    val document = env("KOTLM_CLIENTS_FILE")?.let { path ->
        val file = Path(path)
        if (!Files.isReadable(file)) throw ConfigurationException("KOTLM_CLIENTS_FILE is not readable: $path")
        file.readText()
    } ?: env("KOTLM_CLIENTS")

    if (document == null) {
        throw ConfigurationException(
            "No client keys configured: set KOTLM_CLIENTS_FILE or KOTLM_CLIENTS. " +
                "A proxy without keys would hand the subscription to anyone who can reach the port.",
        )
    }

    val clients = runCatching { json.decodeFromString<ClientsDocument>(document).clients }
        .recoverCatching { json.decodeFromString<List<ClientConfig>>(document) }
        .getOrElse { throw ConfigurationException("Client configuration is not valid JSON: ${it.message}") }

    if (clients.isEmpty()) throw ConfigurationException("Client configuration lists no clients")
    clients.groupBy(ClientConfig::key).forEach { (key, duplicates) ->
        if (duplicates.size > 1) {
            throw ConfigurationException("Two clients share one key: ${duplicates.joinToString { it.name }}")
        }
        if (key.length < 16) {
            throw ConfigurationException("Key of client ${duplicates.first().name} is shorter than 16 characters")
        }
    }
    return clients
}

fun loadConfig(): KotlmConfig {
    val stateDirectory = Path(env("KOTLM_STATE_DIR") ?: "/var/lib/kotlm")
    return KotlmConfig(
        host = env("KOTLM_HOST") ?: "0.0.0.0",
        port = intEnv("KOTLM_PORT", 8080, 1, 65_535),
        authFile = Path(env("KOTLM_AUTH_FILE") ?: "/run/secrets/codex_auth"),
        stateFile = env("KOTLM_STATE_FILE")?.let(::Path) ?: stateDirectory.resolve("auth.json"),
        usageFile = env("KOTLM_USAGE_FILE")?.let(::Path) ?: stateDirectory.resolve("usage.json"),
        clients = readClients(),
        adminKey = env("KOTLM_ADMIN_KEY"),
        allowedModels = (env("KOTLM_ALLOWED_MODELS") ?: "gpt-5.6")
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty),
        maxOutputTokens = intEnv("KOTLM_MAX_OUTPUT_TOKENS", 32_000, 256, 200_000),
        upstreamTimeoutSeconds = intEnv("KOTLM_UPSTREAM_TIMEOUT_SECONDS", 180, 5, 900).toLong(),
    )
}
