package io.heapy.kotlm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.without(vararg keys: String): JsonObject =
    JsonObject(filterKeys { it !in keys })

fun JsonObject.with(vararg entries: Pair<String, JsonElement>): JsonObject =
    JsonObject(toMutableMap().apply { entries.forEach { (key, value) -> put(key, value) } })

/**
 * Responses API text produced by concatenating every output_text block.
 */
fun outputText(response: JsonObject): String =
    response.array("output")
        .orEmpty()
        .filterIsInstance<JsonObject>()
        .filter { it.string("type") == "message" }
        .flatMap { it.array("content").orEmpty().filterIsInstance<JsonObject>() }
        .filter { it.string("type") == "output_text" || it.string("type") == "text" }
        .mapNotNull { it.string("text") }
        .joinToString("")

fun usageTokens(response: JsonObject): Pair<Long, Long> {
    val usage = response.obj("usage") ?: return 0L to 0L
    val input = usage.long("input_tokens") ?: usage.long("prompt_tokens") ?: 0L
    val output = usage.long("output_tokens") ?: usage.long("completion_tokens") ?: 0L
    return input to output
}

/** JWT claims without signature verification; the issued token is used only for exp and account ID. */
fun jwtClaims(token: String): JsonObject {
    val parts = token.split(".")
    if (parts.size < 3) return JsonObject(emptyMap())
    return runCatching {
        val padded = parts[1].padEnd((parts[1].length + 3) / 4 * 4, '=')
        val decoded = java.util.Base64.getUrlDecoder().decode(padded).decodeToString()
        json.parseToJsonElement(decoded).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }
}

fun accountIdFromToken(token: String): String? =
    jwtClaims(token).obj("https://api.openai.com/auth")?.string("chatgpt_account_id")

fun tokenExpiresAt(token: String): Long? =
    (jwtClaims(token)["exp"] as? JsonPrimitive)?.jsonPrimitive?.longOrNull
