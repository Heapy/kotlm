package io.heapy.kotlm

import io.heapy.kotlm.api.promptCacheKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    @Test
    fun `normalizes unique client names`() {
        val clients = parseClients(
            """[{"name":" project-a ","key":"client-key-0123456789"}]""",
        )

        assertEquals("project-a", clients.single().name)
    }

    @Test
    fun `rejects duplicate client names after normalization`() {
        assertFailsWith<ConfigurationException> {
            parseClients(
                """[
                    {"name":"project-a","key":"client-key-0123456789"},
                    {"name":" project-a ","key":"client-key-9876543210"}
                ]""".trimIndent(),
            )
        }
    }

    @Test
    fun `rejects blank and reserved client names`() {
        for (name in listOf("   ", "unknown")) {
            assertFailsWith<ConfigurationException> {
                parseClients("""[{"name":"$name","key":"client-key-0123456789"}]""")
            }
        }
    }

    @Test
    fun `derives a stable opaque prompt cache key from the client key`() {
        val client = ClientConfig(name = "project-a", key = "client-key-0123456789")

        assertEquals(client.promptCacheKey(), client.promptCacheKey())
        assertEquals(64, client.promptCacheKey().length)
        assertEquals(false, client.promptCacheKey().contains(client.key))
        assertEquals(
            false,
            client.promptCacheKey() == ClientConfig("project-a", "another-key-0123456789").promptCacheKey(),
        )
    }
}
