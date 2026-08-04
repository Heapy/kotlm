package io.heapy.kotlm.api

import io.heapy.kotlm.array
import io.heapy.kotlm.int
import io.heapy.kotlm.obj
import io.heapy.kotlm.string
import io.heapy.kotlm.with
import io.heapy.kotlm.without
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RequestException(message: String, val code: String = "invalid_request_error") : RuntimeException(message)

private val REJECTED_INPUT_TYPES = setOf(
    "input_audio", "audio", "input_file", "file", "input_image", "image",
    "computer_call_output", "computer_screenshot", "item_reference", "reasoning",
)

private val REJECTED_INPUT_KEYS = setOf(
    "audio_url", "encrypted_content", "file_id", "file_url", "image_url", "item_id",
)

/**
 * The proxy estimates input from request bytes, so it accepts only text. Images or files
 * would consume tokens whose cost is not visible in the request body.
 */
private fun requireTextOnly(element: JsonElement) {
    when (element) {
        is JsonArray -> element.forEach(::requireTextOnly)
        is JsonObject -> {
            val type = element.string("type")
            if (type != null && type in REJECTED_INPUT_TYPES) {
                throw RequestException("Only text model input is supported")
            }
            if (element.keys.any { it in REJECTED_INPUT_KEYS }) {
                throw RequestException("Only text model input is supported")
            }
            element.values.forEach(::requireTextOnly)
        }
        else -> Unit
    }
}

fun normalizeResponsesPayload(
    payload: JsonObject,
    allowedModels: List<String>,
): JsonObject {
    val model = payload.string("model")
        ?: throw RequestException("model is required")
    if (allowedModels.isNotEmpty() && model !in allowedModels) {
        throw RequestException("Model $model is not allowed; allowed: ${allowedModels.joinToString()}", "model_not_allowed")
    }
    val input = payload["input"] ?: throw RequestException("input is required")
    if (payload.array("tools")?.isNotEmpty() == true) {
        throw RequestException("Provider-hosted tools are not supported")
    }
    for (key in listOf("conversation", "previous_response_id", "prompt")) {
        if (payload[key] != null && payload[key] !is kotlinx.serialization.json.JsonNull) {
            throw RequestException("Remote provider context references are not supported")
        }
    }
    requireTextOnly(input)

    val requested = payload.int("max_output_tokens")
    if (requested != null && requested <= 0) throw RequestException("max_output_tokens must be a positive integer")

    val include = buildJsonArray {
        payload.array("include").orEmpty().forEach(::add)
        if (payload.array("include").orEmpty().none { it is JsonPrimitive && it.content == "reasoning.encrypted_content" }) {
            add(JsonPrimitive("reasoning.encrypted_content"))
        }
    }

    return payload
        // max_output_tokens is valid in the OpenAI contract, but the Codex subscription
        // responds with "Unsupported parameter", so it is not sent upstream.
        .without("stream", "max_output_tokens")
        .with(
            "input" to normalizeInput(input),
            "store" to JsonPrimitive(false),
            "include" to include,
            "reasoning" to (
                payload.obj("reasoning") ?: buildJsonObject {
                    put("effort", "medium")
                    put("summary", "auto")
                }
                ),
        )
}

/**
 * OpenAI accepts a plain string in input, but Codex responds with "Input must be a list".
 * Wrap it here so clients do not need to know this provider-specific behavior.
 */
private fun normalizeInput(input: JsonElement): JsonElement = when (input) {
    is JsonPrimitive -> buildJsonArray {
        add(
            buildJsonObject {
                put("role", "user")
                put("content", input.content)
            },
        )
    }
    else -> input
}

/**
 * chat/completions supports clients with existing SDKs, such as kotbot and tgpt.
 * Messages are converted to Responses input and the result back to chat.completion.
 */
fun chatToResponsesPayload(body: JsonObject): JsonObject {
    val model = body.string("model") ?: throw RequestException("model is required")
    val messages = body.array("messages") ?: throw RequestException("messages are required")

    val instructions = mutableListOf<String>()
    val input = mutableListOf<JsonObject>()
    messages.filterIsInstance<JsonObject>().forEach { message ->
        val content = when (val value = message["content"]) {
            is JsonPrimitive -> value.content
            null -> ""
            else -> value.toString()
        }
        if (message.string("role") == "system" || message.string("role") == "developer") {
            instructions += content
        } else {
            input += buildJsonObject {
                put("role", message.string("role") ?: "user")
                put("content", content)
            }
        }
    }
    if (input.isEmpty()) throw RequestException("messages must contain at least one user message")

    return buildJsonObject {
        put("model", model)
        put("instructions", instructions.joinToString("\n"))
        put("input", JsonArray(input))
        body.string("reasoning_effort")?.let {
            put("reasoning", buildJsonObject { put("effort", it); put("summary", "auto") })
        }
        val format = body.obj("response_format")
        if (format?.string("type") == "json_schema") {
            val schema = format.obj("json_schema") ?: throw RequestException("json_schema is required")
            put(
                "text",
                buildJsonObject {
                    put(
                        "format",
                        buildJsonObject {
                            put("type", "json_schema")
                            put("name", schema.string("name") ?: "response")
                            put("strict", true)
                            put("schema", schema["schema"] ?: throw RequestException("json_schema.schema is required"))
                        },
                    )
                },
            )
        }
    }
}

fun responsesToChatCompletion(response: JsonObject, requestedModel: String, text: String, created: Long): JsonObject =
    buildJsonObject {
        put("id", response.string("id") ?: "chatcmpl_kotlm")
        put("object", "chat.completion")
        put("created", created)
        put("model", response.string("model") ?: requestedModel)
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text)
                            },
                        )
                        put("finish_reason", if (response.string("status") == "incomplete") "length" else "stop")
                    },
                )
            },
        )
        put("usage", response.obj("usage") ?: buildJsonObject { })
    }
