package io.heapy.kotlm

import io.heapy.kotlm.codex.CodexAuthException
import io.heapy.kotlm.codex.CodexCredentials
import io.heapy.kotlm.codex.TokenManager
import io.heapy.kotlm.codex.credentialsExpiring
import io.heapy.kotlm.codex.parseAuthDocument
import io.heapy.kotlm.codex.readCredentials
import io.heapy.kotlm.codex.writeCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexAuthTest {
    @Test
    fun `parses both providers wrapper and flat token file`() {
        val wrapped = parseAuthDocument(
            json.parseToJsonElement(
                """{"providers":{"openai-codex":{"tokens":{"access_token":"a","refresh_token":"r"},"base_url":"https://x/"}}}""",
            ) as JsonObject,
        )
        assertEquals("a", wrapped.accessToken)
        assertEquals("https://x", wrapped.baseUrl)

        val flat = parseAuthDocument(
            json.parseToJsonElement("""{"tokens":{"accessToken":"a2","refreshToken":"r2"}}""") as JsonObject,
        )
        assertEquals("a2", flat.accessToken)
        assertEquals("r2", flat.refreshToken)
    }

    @Test
    fun `rejects a file without a refresh token`() {
        val error = assertFailsWith<CodexAuthException> {
            parseAuthDocument(json.parseToJsonElement("""{"tokens":{"access_token":"a"}}""") as JsonObject)
        }
        assertEquals("auth_tokens_missing", error.code)
        assertTrue(error.reloginRequired)
    }

    @Test
    fun `rotated refresh token survives restart and takes precedence over secret file`() {
        val directory = tempDirectory()
        writeAuthFile(directory, refreshToken = "refresh-old")
        val state = directory.resolve("state/auth.json")

        writeCredentials(
            state,
            CodexCredentials(accessToken = testAccessToken(), refreshToken = "refresh-new", baseUrl = "https://codex.test"),
        )

        val restored = readCredentials(state, directory.resolve("codex_auth.json"))
        assertEquals("refresh-new", restored.refreshToken)
        assertFalse(state.readText().contains("refresh-old"))
    }

    @Test
    fun `refreshes expiring token and leaves fresh token unchanged`() {
        assertTrue(
            credentialsExpiring(
                CodexCredentials(accessToken = testAccessToken(expiresInSeconds = 30), refreshToken = "r"),
                System.currentTimeMillis() / 1_000,
            ),
        )
        assertFalse(
            credentialsExpiring(
                CodexCredentials(accessToken = testAccessToken(expiresInSeconds = 3_600), refreshToken = "r"),
                System.currentTimeMillis() / 1_000,
            ),
        )
    }

    @Test
    fun `refresh writes new refresh token to disk`() = runBlocking {
        val directory = tempDirectory()
        writeAuthFile(directory, accessToken = testAccessToken(expiresInSeconds = 10), refreshToken = "refresh-old")
        val state = directory.resolve("state/auth.json")
        var refreshCalls = 0

        val engine = MockEngine { request ->
            refreshCalls += 1
            assertTrue(request.url.toString().startsWith("https://auth.openai.com/oauth/token"))
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
        }

        val manager = TokenManager(
            authFile = directory.resolve("codex_auth.json"),
            stateFile = state,
            httpClient = HttpClient(engine),
        )

        val credentials = manager.credentials()
        assertEquals("refresh-rotated", credentials.refreshToken)
        assertEquals(1, refreshCalls)
        assertTrue(state.readText().contains("refresh-rotated"))

        // A fresh token does not trigger a second OAuth call.
        manager.credentials()
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `refresh failure with 400 requires another login`() = runBlocking {
        val directory = tempDirectory()
        writeAuthFile(directory, accessToken = testAccessToken(expiresInSeconds = 10))
        val engine = MockEngine { respond(content = "{\"error\":\"invalid_grant\"}", status = HttpStatusCode.BadRequest) }
        val manager = TokenManager(
            authFile = directory.resolve("codex_auth.json"),
            stateFile = directory.resolve("state/auth.json"),
            httpClient = HttpClient(engine),
        )

        val error = assertFailsWith<CodexAuthException> { manager.credentials() }
        assertTrue(error.reloginRequired)
    }

    @Test
    fun `keeps a rotated token in memory and retries a failed state write`() = runBlocking {
        val directory = tempDirectory()
        writeAuthFile(directory, accessToken = testAccessToken(expiresInSeconds = 10), refreshToken = "refresh-old")
        val blockedParent = directory.resolve("state")
        Files.writeString(blockedParent, "not-a-directory")
        val state = blockedParent.resolve("auth.json")
        var refreshCalls = 0
        var persistenceFailures = 0

        val engine = MockEngine {
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
        }
        val manager = TokenManager(
            authFile = directory.resolve("codex_auth.json"),
            stateFile = state,
            httpClient = HttpClient(engine),
            onPersistenceFailure = { persistenceFailures += 1 },
        )

        assertEquals("refresh-rotated", manager.credentials().refreshToken)
        assertEquals("refresh-rotated", manager.credentials().refreshToken)
        assertEquals(1, refreshCalls)
        assertEquals("auth_state_not_persisted", manager.status().error)
        assertTrue(persistenceFailures > 0)

        Files.delete(blockedParent)
        Files.createDirectories(blockedParent)
        assertEquals(null, manager.status().error)
        assertEquals("refresh-rotated", readCredentials(state, directory.resolve("codex_auth.json")).refreshToken)
    }

    @Test
    fun `device credentials remain usable when their first state write fails`() = runBlocking {
        val directory = tempDirectory()
        val blockedParent = directory.resolve("state")
        Files.writeString(blockedParent, "not-a-directory")
        val state = blockedParent.resolve("auth.json")
        val manager = TokenManager(
            authFile = directory.resolve("missing-auth.json"),
            stateFile = state,
            httpClient = HttpClient(MockEngine { throw AssertionError("OAuth must not be called") }),
        )
        val credentials = CodexCredentials(
            accessToken = testAccessToken(),
            refreshToken = "refresh-device",
            baseUrl = "https://codex.test",
        )

        assertFalse(manager.store(credentials))
        assertTrue(manager.status().authenticated)
        assertEquals("auth_state_not_persisted", manager.status().error)

        Files.delete(blockedParent)
        Files.createDirectories(blockedParent)
        assertEquals(null, manager.status().error)
        assertEquals("refresh-device", readCredentials(state, directory.resolve("missing-auth.json")).refreshToken)
    }
}
