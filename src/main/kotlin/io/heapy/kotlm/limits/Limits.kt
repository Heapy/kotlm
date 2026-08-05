package io.heapy.kotlm.limits

import io.heapy.kotlm.json
import io.heapy.kotlm.replaceStateFile
import io.heapy.kotlm.string
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.readText

private val log = LoggerFactory.getLogger(UsageStore::class.java)

data class RateVerdict(val allowed: Boolean, val retryAfterSeconds: Int)

class UsageStateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class UsageStatus(val available: Boolean, val error: String? = null)

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
    private val onPersistenceFailure: (Throwable) -> Unit = {},
) {
    private val mutex = Mutex()
    private var day: String = today()
    private val totals = mutableMapOf<String, Long>()
    private var loaded = false
    private var dirty = false
    private var lastError: Throwable? = null

    private fun load() {
        if (loaded) return
        if (!file.exists()) {
            loaded = true
            clearFailure()
            return
        }

        try {
            val document = json.parseToJsonElement(file.readText()) as? JsonObject
                ?: throw IllegalArgumentException("Usage state is not a JSON object")
            val storedDay = document.string("day")
                ?: throw IllegalArgumentException("Usage state has no day")
            val storedTotals = document["totals"] as? JsonObject
                ?: throw IllegalArgumentException("Usage state has no totals object")
            val parsedTotals = storedTotals.mapValues { (client, value) ->
                val total = (value as? JsonPrimitive)?.longOrNull
                    ?: throw IllegalArgumentException("Usage total of $client is not an integer")
                if (total < 0) throw IllegalArgumentException("Usage total of $client is negative")
                total
            }

            val currentDay = today()
            day = currentDay
            totals.clear()
            if (storedDay == currentDay) {
                totals.putAll(parsedTotals)
            } else {
                dirty = true
            }
            loaded = true
            clearFailure()
        } catch (error: Exception) {
            markFailure(error)
            throw UsageStateException("Usage state is unavailable", error)
        }
    }

    private fun rollOver() {
        val current = today()
        if (current != day) {
            day = current
            totals.clear()
            dirty = true
        }
    }

    private fun persist(): Boolean {
        val document = buildJsonObject {
            put("day", day)
            put("totals", buildJsonObject { totals.forEach { (client, value) -> put(client, value) } })
        }

        return try {
            replaceStateFile(file, json.encodeToString(JsonObject.serializer(), document))
            dirty = false
            clearFailure()
            true
        } catch (error: Exception) {
            dirty = true
            markFailure(error)
            false
        }
    }

    private fun ensureDurable() {
        if (dirty && !persist()) {
            throw UsageStateException("Usage state is unavailable", lastError)
        }
    }

    private fun markFailure(error: Throwable) {
        if (lastError == null) {
            log.error("Could not read or persist daily usage state at {}", file, error)
            onPersistenceFailure(error)
        }
        lastError = error
    }

    private fun clearFailure() {
        if (lastError != null) log.info("Daily usage state is available again: {}", file)
        lastError = null
    }

    suspend fun consumed(client: String): Long = mutex.withLock {
        load()
        rollOver()
        totals[client] ?: 0L
    }

    suspend fun exhausted(client: String, dailyLimit: Long): Boolean = mutex.withLock {
        load()
        rollOver()
        if (dailyLimit > 0) ensureDurable()
        dailyLimit > 0 && (totals[client] ?: 0L) >= dailyLimit
    }

    suspend fun record(client: String, tokens: Long): Boolean = mutex.withLock {
        load()
        rollOver()
        if (tokens <= 0) return true
        totals[client] = (totals[client] ?: 0L) + tokens
        dirty = true
        persist()
    }

    suspend fun status(): UsageStatus = mutex.withLock {
        try {
            load()
            rollOver()
            ensureDurable()
            UsageStatus(available = true)
        } catch (_: UsageStateException) {
            UsageStatus(available = false, error = "usage_state_unavailable")
        }
    }
}
