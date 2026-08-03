@file:JvmName("Application")

package io.heapy.kotlm

import io.heapy.kotlm.api.configureRouting
import io.heapy.kotlm.api.respondError
import io.heapy.kotlm.api.statusOf
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.statuspages.StatusPages
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("io.heapy.kotlm.Application")

fun main() {
    val module = try {
        ApplicationModule()
    } catch (error: ConfigurationException) {
        // Invalid configuration must stop startup. A proxy silently running without
        // keys or writable state is the worst possible outcome.
        log.error("kotlm is misconfigured: {}", error.message)
        exitProcess(1)
    }

    log.info(
        "kotlm on {}:{}, clients: {}, models: {}",
        module.config.host,
        module.config.port,
        module.config.clients.joinToString { it.name },
        module.config.allowedModels.joinToString(),
    )
    if (module.config.adminKey == null) {
        log.warn("KOTLM_ADMIN_KEY is not set: device login and auth status are disabled")
    }

    embeddedServer(
        factory = CIO,
        port = module.config.port,
        host = module.config.host,
        module = { module(module) },
    ).start(wait = true)
}

fun Application.module(module: ApplicationModule) {
    install(StatusPages) {
        exception<Throwable> { call, error ->
            val (status, code) = statusOf(error)
            if (status.value >= 500) log.error("Request failed", error)
            call.respondError(status, code, error.message ?: "Request failed")
        }
    }
    install(MicrometerMetrics) {
        registry = module.registry
    }
    configureRouting(module)
}
