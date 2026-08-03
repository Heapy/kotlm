package io.heapy.kotlm

import io.heapy.kotlm.codex.CodexUpstream
import io.heapy.kotlm.codex.DeviceLogin
import io.heapy.kotlm.codex.TokenManager
import io.heapy.kotlm.limits.RateLimiter
import io.heapy.kotlm.limits.UsageStore
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Manual application assembly keeps the small dependency graph explicit and readable.
 * Tests replace the HTTP client engine while exercising the complete request path.
 */
class ApplicationModule(
    val config: KotlmConfig = loadConfig(),
    engine: HttpClientEngine? = null,
) {
    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val httpClient: HttpClient = if (engine == null) {
        HttpClient(CIO) { upstreamDefaults(config) }
    } else {
        HttpClient(engine) { upstreamDefaults(config) }
    }

    val tokenManager: TokenManager = TokenManager(
        authFile = config.authFile,
        stateFile = config.stateFile,
        httpClient = httpClient,
    )

    val deviceLogin: DeviceLogin = DeviceLogin(httpClient = httpClient, tokenManager = tokenManager)

    val upstream: CodexUpstream = CodexUpstream(httpClient = httpClient, tokenManager = tokenManager)

    val rateLimiter: RateLimiter = RateLimiter()

    val usage: UsageStore = UsageStore(config.usageFile)

    fun countRequest(client: String, endpoint: String, outcome: String) {
        registry.counter("kotlm.requests", "client", client, "endpoint", endpoint, "outcome", outcome).increment()
    }

    fun countTokens(client: String, input: Long, output: Long) {
        if (input > 0) registry.counter("kotlm.tokens", "client", client, "kind", "input").increment(input.toDouble())
        if (output > 0) registry.counter("kotlm.tokens", "client", client, "kind", "output").increment(output.toDouble())
    }

    fun countUpstreamError(code: String) {
        registry.counter("kotlm.upstream.errors", "code", code).increment()
    }
}

private fun HttpClientConfig<*>.upstreamDefaults(config: KotlmConfig) {
    install(HttpTimeout) {
        requestTimeoutMillis = config.upstreamTimeoutSeconds * 1_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = config.upstreamTimeoutSeconds * 1_000
    }
    // The proxy parses provider errors and maps them to client responses instead of throwing.
    expectSuccess = false
}
