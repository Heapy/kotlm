package io.heapy.kotlm

import io.heapy.kotlm.api.RequestException
import io.heapy.kotlm.api.chatToResponsesPayload
import io.heapy.kotlm.api.normalizeResponsesPayload
import io.heapy.kotlm.api.responsesToChatCompletion
import io.heapy.kotlm.api.responsesToChatCompletionStream
import io.heapy.kotlm.codex.UpstreamProtocolException
import kotlinx.serialization.json.JsonNull
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
    fun `upgrades known older models to their current tiers`() {
        val upgrades = mapOf(
            "gpt-5.2" to "gpt-5.6-sol",
            "gpt-5.3-codex" to "gpt-5.6-sol",
            "gpt-5.3-codex-spark" to "gpt-5.6-luna",
            "gpt-5.4" to "gpt-5.6-terra",
            "gpt-5.4-mini" to "gpt-5.6-luna",
            "gpt-5.5" to "gpt-5.6-sol",
        )
        for ((requested, target) in upgrades) {
            val body = payload(
                """{"model":"$requested","input":"hello","reasoning":{"effort":"low"},
                    "text":{"format":{"type":"json_schema","name":"answer","strict":true,"schema":{"type":"object"}}}}""",
            )
            val result = normalizeResponsesPayload(body, DEFAULT_ALLOWED_MODELS)

            assertEquals(target, result.string("model"), requested)
            assertEquals(body["reasoning"], result["reasoning"])
            assertEquals(body["text"], result["text"])
            assertEquals("hello", result.array("input")?.filterIsInstance<JsonObject>()?.single()?.string("content"))
            assertEquals(requested, body.string("model"))
        }
    }

    @Test
    fun `passes current models through unchanged`() {
        for (model in listOf("gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            val result = normalizeResponsesPayload(payload("""{"model":"$model","input":"hello"}"""), DEFAULT_ALLOWED_MODELS)

            assertEquals(model, result.string("model"))
        }
    }

    @Test
    fun `requires the upgrade target to be allowed even when the old model is allowed`() {
        for (allowed in listOf(listOf("gpt-5.4"), listOf("gpt-6-astra"))) {
            val error = assertFailsWith<RequestException> {
                normalizeResponsesPayload(payload("""{"model":"gpt-5.4","input":"hello"}"""), allowed)
            }

            assertEquals("model_not_allowed", error.code)
        }
    }

    @Test
    fun `does not guess upgrades for unknown names or dated variants`() {
        for (model in listOf("gpt-5.4-typo", "gpt-5.4-2026-03-05")) {
            val body = payload("""{"model":"$model","input":"hello"}""")
            val error = assertFailsWith<RequestException> { normalizeResponsesPayload(body, DEFAULT_ALLOWED_MODELS) }

            assertEquals("model_not_allowed", error.code)
            assertEquals(model, normalizeResponsesPayload(body, listOf(model)).string("model"))
        }
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
    fun `extracts text content parts without serializing their JSON`() {
        val result = chatToResponsesPayload(
            payload(
                """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":[
                    {"type":"text","text":"hello "},{"type":"text","text":"world"}
                ]}]}""",
            ),
        )

        val message = result.array("input").orEmpty().filterIsInstance<JsonObject>().single()
        assertEquals("hello world", message.string("content"))
        assertFailsWith<RequestException> {
            chatToResponsesPayload(
                payload(
                    """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":[
                        {"type":"image_url","image_url":{"url":"https://example.test/image.png"}}
                    ]}]}""",
                ),
            )
        }
    }

    @Test
    fun `rejects tool requests before converting Chat payload`() {
        val tools = """[{"type":"function","function":{"name":"lookup"}}]"""
        val error = assertFailsWith<RequestException> {
            chatToResponsesPayload(
                payload("""{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"x"}],"tools":$tools}"""),
            )
        }
        assertEquals("unsupported_parameter", error.code)

        assertFailsWith<RequestException> {
            chatToResponsesPayload(
                payload(
                    """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"x"}],
                        "tools":$tools,"tool_choice":"auto"}""",
                ),
            )
        }

        val disabled = chatToResponsesPayload(
            payload(
                """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"x"}],
                    "tools":$tools,"tool_choice":"none"}""",
            ),
        )
        assertEquals(null, disabled["tools"])
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
            """{"id":"resp_9","model":"gpt-5.6-sol","status":"completed","usage":{
                "input_tokens":3,"output_tokens":4,"total_tokens":7,
                "input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":1}
            }}""",
        ) as JsonObject

        val completion = responsesToChatCompletion(response, "gpt-5.6-sol", "answer", created = 1_700_000_000)
        assertEquals("chat.completion", completion.string("object"))
        assertEquals("resp_9", completion.string("id"))
        val choice = completion.array("choices").orEmpty().filterIsInstance<JsonObject>().single()
        assertEquals("answer", choice.obj("message")?.string("content"))
        assertEquals("stop", choice.string("finish_reason"))
        val usage = completion.obj("usage")
        assertEquals(3, usage?.long("prompt_tokens"))
        assertEquals(4, usage?.long("completion_tokens"))
        assertEquals(7, usage?.long("total_tokens"))
        assertEquals(2, usage?.obj("prompt_tokens_details")?.long("cached_tokens"))
        assertEquals(1, usage?.obj("completion_tokens_details")?.long("reasoning_tokens"))
    }

    @Test
    fun `chat stream reports stop for completed responses`() {
        assertStreamFinishReason("completed", "stop")
    }

    @Test
    fun `chat stream reports length for incomplete responses`() {
        assertStreamFinishReason("incomplete", "length")
    }

    private fun assertStreamFinishReason(status: String, expected: String) {
        val response = payload(
            """{"id":"resp_stream","status":"$status","usage":{"input_tokens":3,"output_tokens":4}}""",
        )
        for (includeUsage in listOf(false, true)) {
            val stream = responsesToChatCompletionStream(
                response = response,
                requestedModel = "gpt-5.6-sol",
                events = listOf(payload("""{"type":"response.output_text.delta","delta":"hello"}""")),
                created = 1,
                includeUsage = includeUsage,
            )
            val data = stream.lineSequence().filter { it.startsWith("data: ") }
                .map { it.removePrefix("data: ") }.toList()
            assertEquals("[DONE]", data.last())
            val chunks = data.dropLast(1).map(::payload)
            assertEquals(if (includeUsage) 4 else 3, chunks.size)
            val choices = chunks.flatMap { it.array("choices").orEmpty().filterIsInstance<JsonObject>() }
            assertEquals(3, choices.size)
            assertEquals("assistant", choices.first().obj("delta")?.string("role"))
            assertEquals("hello", choices[1].obj("delta")?.string("content"))
            choices.dropLast(1).forEach { assertEquals(JsonNull, it["finish_reason"]) }
            assertEquals(expected, choices.last().string("finish_reason"), "includeUsage=$includeUsage")
            assertEquals(payload("{}"), choices.last().obj("delta"))
            if (includeUsage) {
                assertEquals(0, chunks.last().array("choices")?.size)
                assertEquals(7, chunks.last().obj("usage")?.long("total_tokens"))
            }
        }
    }

    @Test
    fun `does not turn a failed Responses result into a Chat success`() {
        val response = payload(
            """{"id":"resp_failed","status":"failed","error":{"message":"generation failed"}}""",
        )

        assertFailsWith<UpstreamProtocolException> {
            responsesToChatCompletion(response, "gpt-5.6-sol", "partial", created = 1)
        }
        assertFailsWith<UpstreamProtocolException> {
            responsesToChatCompletionStream(response, "gpt-5.6-sol", emptyList(), created = 1, includeUsage = false)
        }
    }

    @Test
    fun `rejects a request without messages`() {
        assertFailsWith<RequestException> { chatToResponsesPayload(payload("""{"model":"gpt-5.6-sol","messages":[]}""")) }
    }
}
