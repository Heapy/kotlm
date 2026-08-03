package io.heapy.kotlm.codex

import io.heapy.kotlm.json
import io.heapy.kotlm.obj
import io.heapy.kotlm.string
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class UpstreamProtocolException(message: String) : RuntimeException(message)

/**
 * Codex returns only an event stream, so regular JSON responses must be assembled here.
 * Data lines are collected until an empty line and then parsed as one event.
 */
class SseCollector {
    private val dataParts = mutableListOf<String>()
    private val output = mutableListOf<JsonObject>()
    private var response: JsonObject? = null
    var finished: Boolean = false
        private set

    /** Returns true while the caller should continue reading the stream. */
    fun feed(rawLine: String): Boolean {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isNotEmpty()) {
            if (line.startsWith("data:")) dataParts += line.removePrefix("data:").trimStart()
            return true
        }
        if (dataParts.isEmpty()) return true

        val value = dataParts.joinToString("\n")
        dataParts.clear()
        if (value == "[DONE]") {
            finished = true
            return false
        }

        val event = runCatching { json.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw UpstreamProtocolException("Codex sent an event that is not JSON") }

        when (event.string("type")) {
            "error" -> throw UpstreamProtocolException(event.string("message") ?: "Codex stream failed")
            "response.output_item.done" -> event.obj("item")?.let { output += it }
            "response.completed", "response.incomplete", "response.failed" ->
                event.obj("response")?.let { response = it }
        }
        return true
    }

    fun result(): JsonObject {
        val completed = response ?: throw UpstreamProtocolException("Codex stream ended without a final response")
        val existing = completed["output"] as? JsonArray
        return if ((existing == null || existing.isEmpty()) && output.isNotEmpty()) {
            JsonObject(completed.toMutableMap().apply { put("output", JsonArray(output)) })
        } else {
            completed
        }
    }
}

suspend fun collectResponse(channel: ByteReadChannel): JsonObject {
    val collector = SseCollector()
    while (true) {
        val line = channel.readLine() ?: break
        if (!collector.feed(line)) break
    }
    return collector.result()
}
