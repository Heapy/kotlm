package io.heapy.kotlm

import io.heapy.kotlm.limits.RateLimiter
import io.heapy.kotlm.limits.UsageStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitsTest {
    @Test
    fun `window allows request limit and closes until it advances`() = runBlocking {
        var second = 0L
        val limiter = RateLimiter(windowSeconds = 60, now = { second })

        repeat(3) { assertTrue(limiter.allow("client", limit = 3).allowed) }

        val blocked = limiter.allow("client", limit = 3)
        assertFalse(blocked.allowed)
        assertEquals(60, blocked.retryAfterSeconds)

        // One client does not consume another client's allowance.
        assertTrue(limiter.allow("other", limit = 3).allowed)

        second = 61
        assertTrue(limiter.allow("client", limit = 3).allowed)
    }

    @Test
    fun `zero request limit means unlimited`() = runBlocking {
        val limiter = RateLimiter(now = { 0 })
        repeat(50) { assertTrue(limiter.allow("client", limit = 0).allowed) }
    }

    @Test
    fun `daily usage survives restart and resets next day`() = runBlocking {
        val file = tempDirectory().resolve("usage.json")
        var day = "2026-08-03"

        val store = UsageStore(file, today = { day })
        store.record("sql-nastya", 900)
        store.record("sql-nastya", 200)
        assertEquals(1_100, store.consumed("sql-nastya"))
        assertTrue(store.exhausted("sql-nastya", dailyLimit = 1_000))
        assertFalse(store.exhausted("kotbot", dailyLimit = 1_000))

        val afterRestart = UsageStore(file, today = { day })
        assertEquals(1_100, afterRestart.consumed("sql-nastya"))

        day = "2026-08-04"
        val nextDay = UsageStore(file, today = { day })
        assertEquals(0, nextDay.consumed("sql-nastya"))
        assertFalse(nextDay.exhausted("sql-nastya", dailyLimit = 1_000))
    }

    @Test
    fun `zero token ceiling allows all usage`() = runBlocking {
        val store = UsageStore(tempDirectory().resolve("usage.json"), today = { "2026-08-03" })
        store.record("sql-nastya", 10_000_000)
        assertFalse(store.exhausted("sql-nastya", dailyLimit = 0))
    }
}
