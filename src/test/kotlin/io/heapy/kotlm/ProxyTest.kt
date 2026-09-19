package io.heapy.kotlm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CLIENT_KEY = "client-key-0123456789"
private const val ADMIN_KEY = "admin-key-0123456789"

class ProxyTest {
    private fun sseEngine(): MockEngine = MockEngine { request ->
        when {
            request.url.host == "auth.openai.com" -> respond(
                content = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("access_token", testAccessToken())
                        put("refresh_token", "refresh-rotated")
                    },
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            else -> respond(
                content = ByteReadChannel(sseResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
    }

    private fun applicationModule(
        directory: Path = tempDirectory(),
        engine: MockEngine = sseEngine(),
        clients: List<ClientConfig> = listOf(ClientConfig(name = "sql-nastya", key = CLIENT_KEY)),
        maxRequestBytes: Int = 1_048_576,
    ): ApplicationModule {
        writeAuthFile(directory)
        return ApplicationModule(
            config = testConfig(directory, clients = clients, maxRequestBytes = maxRequestBytes),
            engine = engine,
        )
    }

    private suspend fun ApplicationTestBuilder.ask(
        key: String? = CLIENT_KEY,
        path: String = "/v1/responses",
        body: String = """{"model":"gpt-5.6-sol","input":"hello"}""",
        requestId: String? = null,
    ): HttpResponse = client.post(path) {
        key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        requestId?.let { header("X-Request-Id", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `rejects requests without a project key`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        val response = ask(key = null)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertContains(response.bodyAsText(), "invalid_api_key")

        val wrongKey = ask(key = "not-the-right-key-000")
        assertEquals(HttpStatusCode.Unauthorized, wrongKey.status)
    }

    @Test
    fun `rejects an oversized JSON body before parsing it`() = testApplication {
        val appModule = applicationModule(maxRequestBytes = 64)
        application { module(appModule) }

        val response = ask(body = """{"model":"gpt-5.6-sol","input":"${"x".repeat(100)}"}""")

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertContains(response.bodyAsText(), "request_too_large")
    }

    @Test
    fun `rejects an oversized channel body without content length`() = testApplication {
        val appModule = applicationModule(maxRequestBytes = 64)
        application { module(appModule) }
        val payload = """{"model":"gpt-5.6-sol","input":"${"x".repeat(100)}"}"""

        val response = client.post("/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer $CLIENT_KEY")
            contentType(ContentType.Application.Json)
            setBody(ByteReadChannel(payload))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertContains(response.bodyAsText(), "request_too_large")
    }

    @Test
    fun `assembles provider response from stream and records usage against budget`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        val response = ask()
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()) as JsonObject
        assertEquals("Done", outputText(body))
        assertEquals(18, appModule.usage.consumed("sql-nastya"))
        assertContains(appModule.registry.scrape(), "kotlm_tokens_total")
    }

    @Test
    fun `returns stream events to client instead of a single JSON response`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        val response = ask(body = """{"model":"gpt-5.6-sol","input":"hello","stream":true}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.EventStream.contentType, response.contentType()?.contentType)
        val text = response.bodyAsText()
        assertContains(text, "data: ")
        assertContains(text, "[DONE]")
        // Streaming usage is counted in the same way as a regular response.
        assertEquals(18, appModule.usage.consumed("sql-nastya"))
    }

    @Test
    fun `both endpoints forward upgraded models and report them in JSON and streams`() = testApplication {
        val upstreamModels = mutableListOf<String>()
        val engine = MockEngine { request ->
            val payload = json.parseToJsonElement(request.body.toByteArray().decodeToString()) as JsonObject
            val model = payload.string("model") ?: error("model is required")
            upstreamModels += model
            respond(
                content = ByteReadChannel(sseResponse(model = model)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val appModule = applicationModule(engine = engine)
        application { module(appModule) }

        for ((requested, target) in listOf(
            "gpt-5.4" to "gpt-5.6-terra",
            "gpt-5.3-codex-spark" to "gpt-5.6-luna",
            "gpt-6-astra" to "gpt-6-astra",
        )) {
            for (path in listOf("/v1/responses", "/v1/chat/completions")) {
                for (stream in listOf(false, true)) {
                    val input = if (path == "/v1/responses") {
                        """"input":"hello""""
                    } else {
                        """"messages":[{"role":"user","content":"hello"}]"""
                    }
                    val response = ask(path = path, body = """{"model":"$requested",$input,"stream":$stream}""")

                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals(target, upstreamModels.last())
                    val body = response.bodyAsText()
                    val models = if (stream) {
                        body.lineSequence().filter { it.startsWith("data: ") && it != "data: [DONE]" }
                            .map { json.parseToJsonElement(it.removePrefix("data: ")) as JsonObject }
                            .mapNotNull { it.string("model") ?: it.obj("response")?.string("model") }
                            .toList()
                    } else {
                        listOf((json.parseToJsonElement(body) as JsonObject).string("model"))
                    }
                    assertEquals(listOf(target), models.distinct())
                }
            }
        }
        assertEquals(12, upstreamModels.size)
        assertEquals(12 * 18L, appModule.usage.consumed("sql-nastya"))
    }

    @Test
    fun `chat model fallback uses the upgraded model when provider omits it`() = testApplication {
        val engine = MockEngine { request ->
            val payload = json.parseToJsonElement(request.body.toByteArray().decodeToString()) as JsonObject
            assertEquals("gpt-5.6-terra", payload.string("model"))
            respond(
                content = ByteReadChannel(sseResponse(model = null)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val appModule = applicationModule(engine = engine)
        application { module(appModule) }

        for (stream in listOf(false, true)) {
            val response = ask(
                path = "/v1/chat/completions",
                body = """{"model":"gpt-5.4","messages":[{"role":"user","content":"hello"}],
                    "stream":$stream,"stream_options":{"include_usage":true}}""",
            )

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val chunks = if (stream) {
                body.lineSequence().filter { it.startsWith("data: ") && it != "data: [DONE]" }
                    .map { json.parseToJsonElement(it.removePrefix("data: ")) as JsonObject }.toList()
            } else {
                listOf(json.parseToJsonElement(body) as JsonObject)
            }
            assertTrue(chunks.isNotEmpty())
            assertTrue(chunks.all { it.string("model") == "gpt-5.6-terra" })
        }
    }

    @Test
    fun `rejects disallowed upgrade targets before contacting the provider`() = testApplication {
        var upstreamCalls = 0
        val directory = tempDirectory()
        writeAuthFile(directory)
        val appModule = ApplicationModule(
            config = testConfig(directory, allowedModels = listOf("gpt-5.4")),
            engine = MockEngine {
                upstreamCalls += 1
                error("disallowed upgrade must not reach the provider")
            },
        )
        application { module(appModule) }

        for (stream in listOf(false, true)) {
            val responses = ask(body = """{"model":"gpt-5.4","input":"hello","stream":$stream}""")
            val chat = ask(
                path = "/v1/chat/completions",
                body = """{"model":"gpt-5.4","messages":[{"role":"user","content":"hello"}],"stream":$stream}""",
            )
            for (response in listOf(responses, chat)) {
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertContains(response.bodyAsText(), "model_not_allowed")
            }
        }
        assertEquals(0, upstreamCalls)
    }

    @Test
    fun `closes minute window after limit is exhausted`() = testApplication {
        val appModule = applicationModule(
            clients = listOf(ClientConfig(name = "sql-nastya", key = CLIENT_KEY, requestsPerMinute = 2)),
        )
        application { module(appModule) }

        assertEquals(HttpStatusCode.OK, ask().status)
        assertEquals(HttpStatusCode.OK, ask().status)

        val limited = ask()
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertContains(limited.bodyAsText(), "rate_limit_exceeded")
        assertTrue(limited.headers["Retry-After"]?.toIntOrNull()?.let { it > 0 } == true)
    }

    @Test
    fun `exhausted daily budget blocks access until next day`() = testApplication {
        val appModule = applicationModule(
            clients = listOf(ClientConfig(name = "sql-nastya", key = CLIENT_KEY, dailyTokens = 15)),
        )
        application { module(appModule) }

        assertEquals(HttpStatusCode.OK, ask().status)

        val exhausted = ask()
        assertEquals(HttpStatusCode.TooManyRequests, exhausted.status)
        assertContains(exhausted.bodyAsText(), "daily_token_budget_exhausted")
    }

    @Test
    fun `chat completions returns a response understood by existing SDKs`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        val response = ask(
            path = "/v1/chat/completions",
            body = """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"hello"}]}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()) as JsonObject
        assertEquals("chat.completion", body.string("object"))
        assertEquals(
            "Done",
            body.array("choices").orEmpty().filterIsInstance<JsonObject>().single().obj("message")?.string("content"),
        )
        assertEquals(11, body.obj("usage")?.long("prompt_tokens"))
        assertEquals(7, body.obj("usage")?.long("completion_tokens"))
    }

    @Test
    fun `chat streaming returns Chat Completion chunks and an optional usage chunk`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        val response = ask(
            path = "/v1/chat/completions",
            body = """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"hello"}],
                "stream":true,"stream_options":{"include_usage":true}}""",
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.EventStream.contentType, response.contentType()?.contentType)
        val data = response.bodyAsText().lineSequence()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }
            .toList()
        assertEquals("[DONE]", data.last())
        val chunks = data.dropLast(1).map { json.parseToJsonElement(it) as JsonObject }
        assertTrue(chunks.all { it.string("object") == "chat.completion.chunk" })
        assertEquals(
            "assistant",
            chunks.first().array("choices")?.filterIsInstance<JsonObject>()?.single()?.obj("delta")?.string("role"),
        )
        assertTrue(
            chunks.any {
                it.array("choices")?.filterIsInstance<JsonObject>()?.singleOrNull()?.obj("delta")?.string("content") == "Done"
            },
        )
        val usage = chunks.last().obj("usage")
        assertTrue(chunks.last().array("choices").orEmpty().isEmpty())
        assertEquals(11, usage?.long("prompt_tokens"))
        assertEquals(7, usage?.long("completion_tokens"))
    }

    @Test
    fun `failed provider response is an upstream error for Chat clients`() = testApplication {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(failedSseResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val appModule = applicationModule(engine = engine)
        application { module(appModule) }

        for (stream in listOf(false, true)) {
            val response = ask(
                path = "/v1/chat/completions",
                body = """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"hello"}],"stream":$stream}""",
            )
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertContains(response.bodyAsText(), "upstream_response_failed")
        }
    }

    @Test
    fun `refreshes an expired token and retries the request`() = testApplication {
        var responsesCalls = 0
        var refreshCalls = 0
        val engine = MockEngine { request ->
            if (request.url.host == "auth.openai.com") {
                refreshCalls += 1
                respond(
                    content = json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("access_token", testAccessToken())
                            put("refresh_token", "refresh-rotated")
                        },
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                responsesCalls += 1
                if (responsesCalls == 1) {
                    respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
                } else {
                    respond(
                        content = ByteReadChannel(sseResponse()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }
            }
        }
        val appModule = applicationModule(engine = engine)
        application { module(appModule) }

        assertEquals(HttpStatusCode.OK, ask().status)
        assertEquals(2, responsesCalls)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `uses one stable prompt cache key across request IDs`() = testApplication {
        val promptCacheKeys = mutableListOf<String>()
        val engine = MockEngine { request ->
            val payload = json.parseToJsonElement(request.body.toByteArray().decodeToString()) as JsonObject
            promptCacheKeys += payload.string("prompt_cache_key") ?: error("prompt_cache_key is required")
            respond(
                content = ByteReadChannel(sseResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val appModule = applicationModule(engine = engine)
        application { module(appModule) }

        assertEquals(HttpStatusCode.OK, ask(requestId = "request-one").status)
        assertEquals(HttpStatusCode.OK, ask(requestId = "request-two").status)

        assertEquals(2, promptCacheKeys.size)
        assertEquals(promptCacheKeys.first(), promptCacheKeys.last())
        assertTrue(promptCacheKeys.first() !in setOf("request-one", "request-two"))
    }

    @Test
    fun `does not expose a provider error as a model response`() = testApplication {
        val engine = MockEngine { respond(content = "provider is down", status = HttpStatusCode.BadGateway) }
        val appModule = applicationModule(engine = engine)
        application { module(appModule) }

        val response = ask()
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertContains(response.bodyAsText(), "upstream_error")
    }

    @Test
    fun `returns model list only with a project key`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models").status)

        val response = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $CLIENT_KEY") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()) as JsonObject
        val models = body.array("data").orEmpty().filterIsInstance<JsonObject>()
        assertEquals(
            listOf("gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
            models.mapNotNull { it.string("id") },
        )
        assertTrue(models.all { it.long("created") == 0L })
    }

    @Test
    fun `health exposes subscription status without secrets`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "\"authenticated\":true")
        assertTrue(!body.contains("refresh"), "health must not leak tokens")
    }

    @Test
    fun `liveness responds even without subscription tokens`() = testApplication {
        val appModule = ApplicationModule(config = testConfig(tempDirectory()), engine = sseEngine())
        application { module(appModule) }

        assertEquals(HttpStatusCode.OK, client.get("/live").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health").status)
    }

    @Test
    fun `subscription login requires administrator key`() = testApplication {
        val appModule = applicationModule()
        application { module(appModule) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/status").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/auth/status") { header(HttpHeaders.Authorization, "Bearer $CLIENT_KEY") }.status,
        )

        val allowed = client.get("/auth/status") { header(HttpHeaders.Authorization, "Bearer $ADMIN_KEY") }
        assertEquals(HttpStatusCode.OK, allowed.status)
        assertContains(allowed.bodyAsText(), "\"authenticated\":true")
    }
}
