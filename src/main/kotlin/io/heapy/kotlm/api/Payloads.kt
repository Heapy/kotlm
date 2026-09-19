package io.heapy.kotlm.api

import io.heapy.kotlm.array
import io.heapy.kotlm.boolean
import io.heapy.kotlm.int
import io.heapy.kotlm.json
import io.heapy.kotlm.long
import io.heapy.kotlm.obj
import io.heapy.kotlm.string
import io.heapy.kotlm.with
import io.heapy.kotlm.without
import io.heapy.kotlm.codex.UpstreamProtocolException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RequestException(
    message: String,
    val code: String = "invalid_request_error",
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
) : RuntimeException(message)

// Keep these explicit: model names alone do not establish equivalent capability or cost.
private val MODEL_UPGRADES = mapOf(
    "gpt-5.2" to "gpt-5.6-sol",
    "gpt-5.3-codex" to "gpt-5.6-sol",
    "gpt-5.3-codex-spark" to "gpt-5.6-luna",
    "gpt-5.4" to "gpt-5.6-terra",
    "gpt-5.4-mini" to "gpt-5.6-luna",
    "gpt-5.5" to "gpt-5.6-sol",
)

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
    val requestedModel = payload.string("model")
        ?: throw RequestException("model is required")
    val model = MODEL_UPGRADES[requestedModel] ?: requestedModel
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
            "model" to JsonPrimitive(model),
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
    requireChatToolsDisabled(body)

    val instructions = mutableListOf<String>()
    val input = mutableListOf<JsonObject>()
    messages.forEach { element ->
        val message = element as? JsonObject
            ?: throw RequestException("Every message must be a JSON object")
        val role = message.string("role") ?: throw RequestException("Every message must have a role")
        rejectMessageToolCalls(message)
        val content = chatTextContent(message)

        when (role) {
            "system", "developer" -> instructions += content
            "user", "assistant" -> input += buildJsonObject {
                put("role", role)
                put("content", content)
            }
            "tool", "function" -> throw RequestException(
                "Tool messages are not supported",
                code = "unsupported_parameter",
            )
            else -> throw RequestException("Unsupported message role: $role")
        }
    }
    if (input.isEmpty()) throw RequestException("messages must contain at least one user or assistant message")

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

private fun requireChatToolsDisabled(body: JsonObject) {
    fun configuredArray(key: String): Boolean = when (val value = body[key]) {
        null, JsonNull -> false
        is JsonArray -> value.isNotEmpty()
        else -> throw RequestException("$key must be an array")
    }

    fun explicitlyDisabled(key: String): Boolean = body.string(key) == "none"

    val toolsConfigured = configuredArray("tools")
    val toolChoicePresent = body["tool_choice"] != null && body["tool_choice"] !is JsonNull
    if ((toolsConfigured && !explicitlyDisabled("tool_choice")) ||
        (toolChoicePresent && !explicitlyDisabled("tool_choice"))
    ) {
        throw RequestException("tools and tool_choice are not supported", code = "unsupported_parameter")
    }

    val functionsConfigured = configuredArray("functions")
    val functionChoicePresent = body["function_call"] != null && body["function_call"] !is JsonNull
    if ((functionsConfigured && !explicitlyDisabled("function_call")) ||
        (functionChoicePresent && !explicitlyDisabled("function_call"))
    ) {
        throw RequestException("functions and function_call are not supported", code = "unsupported_parameter")
    }

    if (body["parallel_tool_calls"] != null && body["parallel_tool_calls"] !is JsonNull &&
        body.boolean("parallel_tool_calls") != false
    ) {
        throw RequestException("parallel_tool_calls is not supported", code = "unsupported_parameter")
    }
}

private fun rejectMessageToolCalls(message: JsonObject) {
    val tools = message["tool_calls"]
    if (tools != null && tools !is JsonNull && (tools !is JsonArray || tools.isNotEmpty())) {
        throw RequestException("Assistant tool calls are not supported", code = "unsupported_parameter")
    }
    val function = message["function_call"]
    if (function != null && function !is JsonNull) {
        throw RequestException("Assistant function calls are not supported", code = "unsupported_parameter")
    }
}

private fun chatTextContent(message: JsonObject): String = when (val content = message["content"]) {
    is JsonPrimitive -> {
        if (!content.isString) throw RequestException("Message content must be text")
        content.content
    }
    is JsonArray -> {
        if (content.isEmpty()) throw RequestException("Message content parts must not be empty")
        content.joinToString(separator = "") { element ->
            val part = element as? JsonObject
                ?: throw RequestException("Every message content part must be a JSON object")
            if (part.string("type") != "text") {
                throw RequestException("Only text message content parts are supported", code = "unsupported_parameter")
            }
            part.string("text") ?: throw RequestException("Text message content parts require text")
        }
    }
    else -> throw RequestException("Message content must be text or an array of text parts")
}

private fun chatUsage(response: JsonObject): JsonObject? {
    val usage = response.obj("usage") ?: return null
    val prompt = usage.long("input_tokens") ?: usage.long("prompt_tokens") ?: 0
    val completion = usage.long("output_tokens") ?: usage.long("completion_tokens") ?: 0
    return buildJsonObject {
        put("prompt_tokens", prompt)
        put("completion_tokens", completion)
        put("total_tokens", usage.long("total_tokens") ?: prompt + completion)
        (usage.obj("input_tokens_details") ?: usage.obj("prompt_tokens_details"))?.let {
            put("prompt_tokens_details", it)
        }
        (usage.obj("output_tokens_details") ?: usage.obj("completion_tokens_details"))?.let {
            put("completion_tokens_details", it)
        }
    }
}

private fun chatFinishReason(response: JsonObject): String =
    if (response.string("status") == "incomplete") "length" else "stop"

private fun requireChatResponseSucceeded(response: JsonObject) {
    if (response.string("status") == "failed") {
        throw UpstreamProtocolException(
            response.obj("error")?.string("message") ?: "Model provider failed to generate a response",
        )
    }
}

fun responsesToChatCompletion(response: JsonObject, requestedModel: String, text: String, created: Long): JsonObject {
    requireChatResponseSucceeded(response)
    return buildJsonObject {
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
                        put("finish_reason", chatFinishReason(response))
                    },
                )
            },
        )
        chatUsage(response)?.let { put("usage", it) }
    }
}

fun responsesToChatCompletionStream(
    response: JsonObject,
    requestedModel: String,
    events: List<JsonObject>,
    created: Long,
    includeUsage: Boolean,
): String {
    requireChatResponseSucceeded(response)
    val id = response.string("id") ?: "chatcmpl_kotlm"
    val model = response.string("model") ?: requestedModel

    fun chunk(delta: JsonObject, finishReason: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        finishReason?.let { put("finish_reason", it) } ?: put("finish_reason", JsonNull)
                    },
                )
            },
        )
        if (includeUsage) put("usage", JsonNull)
    }

    val deltas = events.mapNotNull { event ->
        when (event.string("type")) {
            "response.output_text.delta" -> event.string("delta")?.let { "content" to it }
            "response.refusal.delta" -> event.string("delta")?.let { "refusal" to it }
            else -> null
        }
    }.toMutableList()
    if (deltas.isEmpty()) {
        val text = io.heapy.kotlm.outputText(response)
        if (text.isNotEmpty()) deltas += "content" to text
    }

    return buildString {
        fun appendEvent(value: JsonObject) {
            append("data: ")
            append(json.encodeToString(JsonObject.serializer(), value))
            append("\n\n")
        }

        appendEvent(chunk(buildJsonObject { put("role", "assistant") }))
        deltas.forEach { (key, value) -> appendEvent(chunk(buildJsonObject { put(key, value) })) }
        appendEvent(chunk(buildJsonObject { }, chatFinishReason(response)))
        if (includeUsage) {
            chatUsage(response)?.let { usage ->
                appendEvent(
                    buildJsonObject {
                        put("id", id)
                        put("object", "chat.completion.chunk")
                        put("created", created)
                        put("model", model)
                        put("choices", buildJsonArray { })
                        put("usage", usage)
                    },
                )
            }
        }
        append("data: [DONE]\n\n")
    }
}
