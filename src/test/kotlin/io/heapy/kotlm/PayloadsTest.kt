package io.heapy.kotlm

import io.heapy.kotlm.api.RequestException
import io.heapy.kotlm.api.chatToResponsesPayload
import io.heapy.kotlm.api.normalizeResponsesPayload
import io.heapy.kotlm.api.responsesToChatCompletion
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PayloadsTest {
    private fun payload(body: String): JsonObject = json.parseToJsonElement(body) as JsonObject

    private fun normalize(body: String) =
        normalizeResponsesPayload(payload(body), listOf("gpt-5.6-sol"))

    @Test
    fun `forces store false and removes output ceiling`() {
        val result = normalize("""{"model":"gpt-5.6-sol","input":"hello","store":true,"max_output_tokens":100000}""")

        assertEquals(false, result.boolean("store"))
        // The Codex subscription responds to max_output_tokens with "Unsupported parameter".
        assertEquals(null, result["max_output_tokens"])
        assertTrue(result.array("include").orEmpty().any { it.toString().contains("reasoning.encrypted_content") })
    }

    @Test
    fun `converts input string to list accepted by Codex`() {
        val result = normalize("""{"model":"gpt-5.6-sol","input":"hello"}""")

        val input = result.array("input").orEmpty().filterIsInstance<JsonObject>()
        assertEquals(1, input.size)
        assertEquals("user", input.single().string("role"))
        assertEquals("hello", input.single().string("content"))
    }

    @Test
    fun `leaves existing input list unchanged`() {
        val result = normalize("""{"model":"gpt-5.6-sol","input":[{"role":"user","content":[{"type":"input_text","text":"hello"}]}]}""")

        val content = result.array("input").orEmpty().filterIsInstance<JsonObject>().single().array("content")
        assertEquals("input_text", content.orEmpty().filterIsInstance<JsonObject>().single().string("type"))
    }

    @Test
    fun `still rejects a meaningless output ceiling`() {
        assertFailsWith<RequestException> { normalize("""{"model":"gpt-5.6-sol","input":"x","max_output_tokens":0}""") }
    }

    @Test
    fun `rejects a model outside the allowed list`() {
        val error = assertFailsWith<RequestException> {
            normalize("""{"model":"gpt-4o","input":"hello"}""")
        }
        assertEquals("model_not_allowed", error.code)
    }

    @Test
    fun `rejects provider tools and non-text input`() {
        assertFailsWith<RequestException> {
            normalize("""{"model":"gpt-5.6-sol","input":"x","tools":[{"type":"web_search"}]}""")
        }
        assertFailsWith<RequestException> {
            normalize("""{"model":"gpt-5.6-sol","input":[{"role":"user","content":[{"type":"input_image","image_url":"http://x"}]}]}""")
        }
        assertFailsWith<RequestException> {
            normalize("""{"model":"gpt-5.6-sol","input":"x","previous_response_id":"resp_1"}""")
        }
    }

    @Test
    fun `preserves the client strict JSON schema`() {
        val result = normalize(
            """{"model":"gpt-5.6-sol","input":"x","text":{"format":{"type":"json_schema","name":"review","strict":true,"schema":{"type":"object"}}}}""",
        )
        assertEquals("review", result.obj("text")?.obj("format")?.string("name"))
        assertEquals(true, result.obj("text")?.obj("format")?.boolean("strict"))
    }

    @Test
    fun `converts chat messages to Responses input`() {
        val result = chatToResponsesPayload(
            payload(
                """{"model":"gpt-5.6-sol","messages":[
                    {"role":"system","content":"you are an assistant"},
                    {"role":"user","content":"hello"}
                ],"max_tokens":500,"reasoning_effort":"low"}""",
            ),
        )

        assertEquals("you are an assistant", result.string("instructions"))
        assertEquals("low", result.obj("reasoning")?.string("effort"))
        val input = result.array("input").orEmpty().filterIsInstance<JsonObject>()
        assertEquals(1, input.size)
        assertEquals("user", input.single().string("role"))
        assertEquals("hello", input.single().string("content"))
    }

    @Test
    fun `converts response_format json_schema to strict Responses format`() {
        val result = chatToResponsesPayload(
            payload(
                """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"x"}],
                    "response_format":{"type":"json_schema","json_schema":{"name":"verdict","schema":{"type":"object"}}}}""",
            ),
        )
        assertEquals("verdict", result.obj("text")?.obj("format")?.string("name"))
    }

    @Test
    fun `converts provider response to chat completion`() {
        val response = json.parseToJsonElement(
            """{"id":"resp_9","model":"gpt-5.6-sol","status":"completed","usage":{"input_tokens":3,"output_tokens":4}}""",
        ) as JsonObject

        val completion = responsesToChatCompletion(response, "gpt-5.6-sol", "answer", created = 1_700_000_000)
        assertEquals("chat.completion", completion.string("object"))
        assertEquals("resp_9", completion.string("id"))
        val choice = completion.array("choices").orEmpty().filterIsInstance<JsonObject>().single()
        assertEquals("answer", choice.obj("message")?.string("content"))
        assertEquals("stop", choice.string("finish_reason"))
    }

    @Test
    fun `rejects a request without messages`() {
        assertFailsWith<RequestException> { chatToResponsesPayload(payload("""{"model":"gpt-5.6-sol","messages":[]}""")) }
    }
}
