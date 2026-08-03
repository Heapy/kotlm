package io.heapy.kotlm

import io.heapy.kotlm.codex.SseCollector
import io.heapy.kotlm.codex.UpstreamProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SseTest {
    private fun collect(stream: String): SseCollector {
        val collector = SseCollector()
        for (line in stream.split("\n")) {
            if (!collector.feed(line)) break
        }
        return collector
    }

    @Test
    fun `assembles final response from event stream`() {
        val collector = collect(sseResponse(text = "Done", inputTokens = 11, outputTokens = 7))
        val result = collector.result()

        assertEquals("Done", outputText(result))
        assertEquals(11L to 7L, usageTokens(result))
        assertTrue(collector.finished)
    }

    @Test
    fun `uses output from individual events when final event omits it`() {
        val stream = buildString {
            append("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"fragment\"}]}}\n\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"output\":[]}}\n\n")
            append("data: [DONE]\n\n")
        }

        assertEquals("fragment", outputText(collect(stream).result()))
    }

    @Test
    fun `turns error event into an exception instead of an empty response`() {
        val error = assertFailsWith<UpstreamProtocolException> {
            collect("data: {\"type\":\"error\",\"message\":\"Codex failed\"}\n\n").result()
        }
        assertEquals("Codex failed", error.message)
    }

    @Test
    fun `does not treat a truncated stream as a successful response`() {
        assertFailsWith<UpstreamProtocolException> {
            collect("data: {\"type\":\"response.created\"}\n\n").result()
        }
    }
}
