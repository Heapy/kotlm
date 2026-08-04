package io.heapy.kotlm.api

import io.heapy.kotlm.ApplicationModule
import io.heapy.kotlm.ClientConfig
import io.heapy.kotlm.boolean
import io.heapy.kotlm.codex.CodexAuthException
import io.heapy.kotlm.codex.DevicePollResult
import io.heapy.kotlm.codex.SseCollector
import io.heapy.kotlm.codex.UpstreamProtocolException
import io.heapy.kotlm.codex.collectResponse
import io.heapy.kotlm.json
import io.heapy.kotlm.outputText
import io.heapy.kotlm.string
import io.heapy.kotlm.usageTokens
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.application.Application
import io.ktor.utils.io.readLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private const val UPSTREAM_ERROR_PREVIEW = 300
private const val MAX_STREAM_CHARACTERS = 8_000_000

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    retryAfterSeconds: Int? = null,
) {
    retryAfterSeconds?.let { response.headers.append("Retry-After", it.toString()) }
    respondText(
        text = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("message", message)
                        put("type", if (status.value >= 500) "api_error" else "invalid_request_error")
                        put("code", code)
                    },
                )
            },
        ),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

private fun ApplicationCall.bearerKey(): String? =
    request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf(String::isNotEmpty)

private fun secretsEqual(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.toByteArray(), right.toByteArray())

private fun ApplicationCall.resolveClient(module: ApplicationModule): ClientConfig? {
    val key = bearerKey() ?: return null
    return module.config.clients.firstOrNull { secretsEqual(it.key, key) }
}

private fun ApplicationCall.isAdmin(module: ApplicationModule): Boolean {
    val adminKey = module.config.adminKey ?: return false
    val key = bearerKey() ?: return false
    return secretsEqual(adminKey, key)
}

private suspend fun ApplicationCall.receiveJsonObject(): JsonObject =
    runCatching { json.parseToJsonElement(receiveText()) as JsonObject }
        .getOrElse { throw RequestException("Request body must be a JSON object") }

/**
 * Shared admission path for both routes. The project key, minute window, and daily
 * ceiling are checked before contacting the subscription so a noisy client cannot consume it.
 */
private suspend fun ApplicationCall.admitted(module: ApplicationModule, endpoint: String): ClientConfig? {
    val client = resolveClient(module)
    if (client == null) {
        module.countRequest("unknown", endpoint, "unauthorized")
        respondError(HttpStatusCode.Unauthorized, "invalid_api_key", "Unknown or missing API key")
        return null
    }

    val verdict = module.rateLimiter.allow(client.name, client.requestsPerMinute)
    if (!verdict.allowed) {
        module.countRequest(client.name, endpoint, "rate_limited")
        respondError(
            HttpStatusCode.TooManyRequests,
            "rate_limit_exceeded",
            "Client ${client.name} is limited to ${client.requestsPerMinute} requests per minute",
            verdict.retryAfterSeconds,
        )
        return null
    }

    if (module.usage.exhausted(client.name, client.dailyTokens)) {
        module.countRequest(client.name, endpoint, "budget_exhausted")
        respondError(
            HttpStatusCode.TooManyRequests,
            "daily_token_budget_exhausted",
            "Client ${client.name} spent its daily budget of ${client.dailyTokens} tokens",
        )
        return null
    }

    return client
}

private suspend fun ApplicationCall.relayUpstreamError(module: ApplicationModule, response: HttpResponse, endpoint: String, client: String) {
    val preview = runCatching { response.bodyAsText().take(UPSTREAM_ERROR_PREVIEW) }.getOrDefault("")
    module.countUpstreamError("http_${response.status.value}")
    module.countRequest(client, endpoint, "upstream_error")
    respondError(
        HttpStatusCode.fromValue(if (response.status.value == 429) 429 else 502),
        "upstream_error",
        "Model provider returned HTTP ${response.status.value}${if (preview.isBlank()) "" else ": $preview"}",
    )
}

private suspend fun recordUsage(module: ApplicationModule, client: String, result: JsonObject) {
    val (input, output) = usageTokens(result)
    module.countTokens(client, input, output)
    module.usage.record(client, input + output)
}

fun Application.configureRouting(module: ApplicationModule) = routing {
    get("/health") {
        val status = module.tokenManager.status()
        call.respondText(
            text = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("status", if (status.authenticated) "ok" else "degraded")
                    put("authenticated", status.authenticated)
                    status.expiresInSeconds?.let { put("tokenExpiresInSeconds", it) }
                    status.error?.let { put("error", it) }
                    put("clients", module.config.clients.size)
                },
            ),
            contentType = ContentType.Application.Json,
            status = if (status.authenticated) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }

    // Docker liveness probe. It is separate from /health: missing subscription tokens
    // should raise an alert, but should not restart the container repeatedly.
    get("/live") {
        call.respondText("OK", ContentType.Text.Plain)
    }

    get("/metrics") {
        call.respondText(module.registry.scrape(), ContentType.Text.Plain)
    }

    route("/v1") {
        get("/models") {
            call.admitted(module, "models") ?: return@get
            call.respondText(
                text = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("object", "list")
                        put(
                            "data",
                            buildJsonArray {
                                module.config.allowedModels.forEach { model ->
                                    add(
                                        buildJsonObject {
                                            put("id", model)
                                            put("object", "model")
                                            put("owned_by", "openai-codex")
                                        },
                                    )
                                }
                            },
                        )
                    },
                ),
                contentType = ContentType.Application.Json,
            )
        }

        post("/responses") {
            val client = call.admitted(module, "responses") ?: return@post
            val body = call.receiveJsonObject()
            val payload = normalizeResponsesPayload(body, module.config.allowedModels)
            val streaming = body.boolean("stream") == true
            val requestId = call.request.headers["X-Request-Id"] ?: UUID.randomUUID().toString()

            module.upstream.stream(payload, requestId) { response ->
                if (response.status.value >= 400) {
                    call.relayUpstreamError(module, response, "responses", client.name)
                    return@stream
                }

                if (streaming) {
                    // The client receives the same events in one chunk. They cannot be
                    // forwarded as they arrive because respondBytesWriter writes lazily,
                    // after leaving the provider request block and closing its connection.
                    // Read the entire stream here, which also accounts for tokens accurately.
                    val collector = SseCollector()
                    val events = StringBuilder()
                    val upstreamChannel = response.bodyAsChannel()
                    while (true) {
                        val line = upstreamChannel.readLine() ?: break
                        events.append(line).append('\n')
                        if (events.length > MAX_STREAM_CHARACTERS) {
                            throw UpstreamProtocolException("Model provider sent more than $MAX_STREAM_CHARACTERS characters")
                        }
                        runCatching { collector.feed(line) }
                    }
                    runCatching { collector.result() }.onSuccess { recordUsage(module, client.name, it) }
                    module.countRequest(client.name, "responses", "streamed")
                    call.respondText(events.toString(), ContentType.Text.EventStream)
                } else {
                    val result = collectResponse(response.bodyAsChannel())
                    recordUsage(module, client.name, result)
                    module.countRequest(client.name, "responses", "ok")
                    call.respondText(
                        text = json.encodeToString(JsonObject.serializer(), result),
                        contentType = ContentType.Application.Json,
                    )
                }
            }
        }

        post("/chat/completions") {
            val client = call.admitted(module, "chat") ?: return@post
            val body = call.receiveJsonObject()
            val payload = normalizeResponsesPayload(chatToResponsesPayload(body), module.config.allowedModels)
            val requestId = call.request.headers["X-Request-Id"] ?: UUID.randomUUID().toString()

            module.upstream.stream(payload, requestId) { response ->
                if (response.status.value >= 400) {
                    call.relayUpstreamError(module, response, "chat", client.name)
                    return@stream
                }
                val result = collectResponse(response.bodyAsChannel())
                recordUsage(module, client.name, result)
                module.countRequest(client.name, "chat", "ok")
                call.respondText(
                    text = json.encodeToString(
                        JsonObject.serializer(),
                        responsesToChatCompletion(
                            response = result,
                            requestedModel = body.string("model") ?: "",
                            text = outputText(result),
                            created = System.currentTimeMillis() / 1_000,
                        ),
                    ),
                    contentType = ContentType.Application.Json,
                )
            }
        }
    }

    // Subscription login and status require the administrator key because these routes
    // modify tokens rather than send model requests.
    route("/auth") {
        get("/status") {
            if (!call.isAdmin(module)) return@get call.respondError(
                HttpStatusCode.Unauthorized, "invalid_admin_key", "Admin key required",
            )
            val status = module.tokenManager.status()
            call.respondText(
                text = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("authenticated", status.authenticated)
                        status.expiresInSeconds?.let { put("expiresInSeconds", it) }
                        status.accountId?.let { put("accountId", it) }
                        status.error?.let { put("error", it) }
                    },
                ),
                contentType = ContentType.Application.Json,
            )
        }

        post("/device/start") {
            if (!call.isAdmin(module)) return@post call.respondError(
                HttpStatusCode.Unauthorized, "invalid_admin_key", "Admin key required",
            )
            val challenge = module.deviceLogin.start()
            call.respondText(
                text = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", challenge.id)
                        put("userCode", challenge.userCode)
                        put("verificationUrl", challenge.verificationUrl)
                        put("intervalSeconds", challenge.intervalSeconds)
                    },
                ),
                contentType = ContentType.Application.Json,
            )
        }

        post("/device/poll") {
            if (!call.isAdmin(module)) return@post call.respondError(
                HttpStatusCode.Unauthorized, "invalid_admin_key", "Admin key required",
            )
            val id = call.receiveJsonObject().string("id")
                ?: throw RequestException("id of the device login is required")
            val result = module.deviceLogin.poll(id)
            call.respondText(
                text = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        when (result) {
                            is DevicePollResult.Pending -> {
                                put("status", "pending")
                                put("retryAfterSeconds", result.retryAfterSeconds)
                            }
                            DevicePollResult.Complete -> put("status", "complete")
                        }
                    },
                ),
                contentType = ContentType.Application.Json,
            )
        }
    }
}

fun statusOf(error: Throwable): Pair<HttpStatusCode, String> = when (error) {
    is RequestException -> HttpStatusCode.BadRequest to error.code
    is CodexAuthException -> when {
        error.reloginRequired -> HttpStatusCode.Unauthorized
        error.code == "codex_rate_limited" -> HttpStatusCode.TooManyRequests
        else -> HttpStatusCode.ServiceUnavailable
    } to error.code
    is UpstreamProtocolException -> HttpStatusCode.BadGateway to "invalid_upstream_response"
    // A missing provider response or dropped connection indicates upstream unavailability,
    // not an internal proxy error.
    is IOException -> HttpStatusCode.ServiceUnavailable to "upstream_unavailable"
    else -> HttpStatusCode.InternalServerError to "internal_error"
}
