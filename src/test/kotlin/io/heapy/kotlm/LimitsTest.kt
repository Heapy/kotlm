package io.heapy.kotlm

import io.heapy.kotlm.limits.RateLimiter
import io.heapy.kotlm.limits.UsageStateException
import io.heapy.kotlm.limits.UsageStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `failed usage write blocks limited requests until persistence recovers`() = runBlocking {
        val directory = tempDirectory()
        val blockedParent = directory.resolve("state")
        Files.writeString(blockedParent, "not-a-directory")
        val file = blockedParent.resolve("usage.json")
        var persistenceFailures = 0
        val store = UsageStore(
            file = file,
            today = { "2026-08-03" },
            onPersistenceFailure = { persistenceFailures += 1 },
        )

        assertFalse(store.record("sql-nastya", 250))
        assertEquals(250, store.consumed("sql-nastya"))
        assertFailsWith<UsageStateException> {
            store.exhausted("sql-nastya", dailyLimit = 1_000)
        }
        assertEquals(1, persistenceFailures)

        Files.delete(blockedParent)
        Files.createDirectories(blockedParent)
        assertFalse(store.exhausted("sql-nastya", dailyLimit = 1_000))
        assertEquals(250, UsageStore(file, today = { "2026-08-03" }).consumed("sql-nastya"))
    }

    @Test
    fun `invalid usage state is unavailable instead of resetting the budget`() = runBlocking {
        val file = tempDirectory().resolve("usage.json")
        file.writeText("not-json")
        val store = UsageStore(file, today = { "2026-08-03" })

        assertFailsWith<UsageStateException> {
            store.exhausted("sql-nastya", dailyLimit = 1_000)
        }
        assertFalse(store.status().available)
        assertEquals("usage_state_unavailable", store.status().error)
    }
}
