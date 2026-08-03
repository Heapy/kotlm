package io.heapy.kotlm.limits

import io.heapy.kotlm.json
import io.heapy.kotlm.string
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class RateVerdict(val allowed: Boolean, val retryAfterSeconds: Int)

/**
 * Per-client sliding window kept in memory. It starts fresh after a restart, an acceptable
 * tradeoff because the minute limit protects against request storms rather than a malicious
 * client whose key can simply be revoked.
 */
class RateLimiter(
    private val windowSeconds: Int = 60,
    private val now: () -> Long = { System.nanoTime() / 1_000_000_000 },
) {
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, ArrayDeque<Long>>()

    suspend fun allow(key: String, limit: Int): RateVerdict = mutex.withLock {
        if (limit <= 0) return RateVerdict(allowed = true, retryAfterSeconds = 0)
        val moment = now()
        val window = requests.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && window.first() <= moment - windowSeconds) window.removeFirst()

        if (window.size >= limit) {
            val retryAfter = (windowSeconds - (moment - window.first())).coerceAtLeast(1)
            RateVerdict(allowed = false, retryAfterSeconds = retryAfter.toInt())
        } else {
            window.addLast(moment)
            RateVerdict(allowed = true, retryAfterSeconds = 0)
        }
    }
}

/**
 * Daily token usage by project. It survives restarts; otherwise restarting the container
 * would reset the daily ceiling and make the limit meaningless.
 */
class UsageStore(
    private val file: Path,
    private val today: () -> String = { Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString() },
) {
    private val mutex = Mutex()
    private var day: String = today()
    private val totals = mutableMapOf<String, Long>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val document = runCatching { json.parseToJsonElement(file.readText()) as JsonObject }.getOrNull() ?: return
        val storedDay = document.string("day") ?: return
        if (storedDay != today()) return
        day = storedDay
        (document["totals"] as? JsonObject)?.forEach { (client, value) ->
            (value as? JsonPrimitive)?.longOrNull?.let { totals[client] = it }
        }
    }

    private fun rollOver() {
        val current = today()
        if (current != day) {
            day = current
            totals.clear()
        }
    }

    private fun persist() {
        val document = buildJsonObject {
            put("day", day)
            put("totals", buildJsonObject { totals.forEach { (client, value) -> put(client, value) } })
        }
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            temporary.writeText(json.encodeToString(JsonObject.serializer(), document))
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    suspend fun consumed(client: String): Long = mutex.withLock {
        load()
        rollOver()
        totals[client] ?: 0L
    }

    suspend fun exhausted(client: String, dailyLimit: Long): Boolean = mutex.withLock {
        load()
        rollOver()
        dailyLimit > 0 && (totals[client] ?: 0L) >= dailyLimit
    }

    suspend fun record(client: String, tokens: Long): Unit = mutex.withLock {
        load()
        rollOver()
        if (tokens <= 0) return
        totals[client] = (totals[client] ?: 0L) + tokens
        persist()
    }
}
