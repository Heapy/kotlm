package io.heapy.kotlm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
    ): ApplicationModule {
        writeAuthFile(directory)
        return ApplicationModule(config = testConfig(directory, clients = clients), engine = engine)
    }

    private suspend fun ApplicationTestBuilder.ask(
        key: String? = CLIENT_KEY,
        path: String = "/v1/responses",
        body: String = """{"model":"gpt-5.6-sol","input":"hello"}""",
    ): HttpResponse = client.post(path) {
        key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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
        assertContains(response.bodyAsText(), "gpt-5.6-sol")
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
