package io.heapy.kotlm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.writeText

fun base64Url(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

/** The token is externally signed and never verified here; only its claims matter. */
fun testAccessToken(expiresInSeconds: Long = 3_600, accountId: String = "acc-1"): String {
    val header = base64Url("""{"alg":"none"}""")
    val payload = base64Url(
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("exp", System.currentTimeMillis() / 1_000 + expiresInSeconds)
                put(
                    "https://api.openai.com/auth",
                    buildJsonObject { put("chatgpt_account_id", accountId) },
                )
            },
        ),
    )
    return "$header.$payload.signature"
}

fun tempDirectory(): Path = Files.createTempDirectory("kotlm-test")

fun writeAuthFile(directory: Path, accessToken: String = testAccessToken(), refreshToken: String = "refresh-1"): Path {
    val file = directory.resolve("codex_auth.json")
    file.writeText(
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "providers",
                    buildJsonObject {
                        put(
                            "openai-codex",
                            buildJsonObject {
                                put(
                                    "tokens",
                                    buildJsonObject {
                                        put("access_token", accessToken)
                                        put("refresh_token", refreshToken)
                                    },
                                )
                                put("base_url", "https://codex.test/backend-api/codex")
                            },
                        )
                    },
                )
            },
        ),
    )
    return file
}

fun testConfig(
    directory: Path,
    clients: List<ClientConfig> = listOf(ClientConfig(name = "sql-nastya", key = "client-key-0123456789")),
    allowedModels: List<String> = listOf("gpt-5.6-sol"),
    adminKey: String? = "admin-key-0123456789",
): KotlmConfig = KotlmConfig(
    host = "127.0.0.1",
    port = 0,
    authFile = directory.resolve("codex_auth.json"),
    stateFile = directory.resolve("state/auth.json"),
    usageFile = directory.resolve("state/usage.json"),
    clients = clients,
    adminKey = adminKey,
    allowedModels = allowedModels,
    upstreamTimeoutSeconds = 30,
)

/** Codex response event stream from which the proxy assembles regular JSON. */
fun sseResponse(
    text: String = "Done",
    inputTokens: Int = 11,
    outputTokens: Int = 7,
    model: String = "gpt-5.6-sol",
): String {
    val response = buildJsonObject {
        put("id", "resp_1")
        put("object", "response")
        put("status", "completed")
        put("model", model)
        put(
            "output",
            kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "message")
                        put("role", "assistant")
                        put(
                            "content",
                            kotlinx.serialization.json.buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "output_text")
                                        put("text", text)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        put(
            "usage",
            buildJsonObject {
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
                put("total_tokens", inputTokens + outputTokens)
            },
        )
    }
    val completed = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("type", "response.completed")
            put("response", response)
        },
    )
    return buildString {
        append("event: response.created\n")
        append("data: {\"type\":\"response.created\"}\n\n")
        append("data: ").append(completed).append("\n\n")
        append("data: [DONE]\n\n")
    }
}
