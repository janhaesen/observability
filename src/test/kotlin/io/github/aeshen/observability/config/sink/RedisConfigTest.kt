package io.github.aeshen.observability.config.sink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RedisConfigTest {
    @Test
    fun `valid config constructs successfully`() {
        val config = Redis(uri = "redis://localhost:6379", streamKey = "events")
        assertEquals("redis://localhost:6379", config.uri)
        assertEquals("events", config.streamKey)
        assertNull(config.maxlen)
        assertEquals(5_000L, config.timeoutMillis)
    }

    @Test
    fun `maxlen can be specified`() {
        val config = Redis(uri = "redis://localhost:6379", streamKey = "events", maxlen = 10_000)
        assertEquals(10_000L, config.maxlen)
    }

    @Test
    fun `blank uri is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Redis(uri = "  ", streamKey = "events")
        }
    }

    @Test
    fun `blank streamKey is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Redis(uri = "redis://localhost:6379", streamKey = "")
        }
    }

    @Test
    fun `zero timeoutMillis is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Redis(uri = "redis://localhost:6379", streamKey = "events", timeoutMillis = 0)
        }
    }

    @Test
    fun `zero maxlen is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Redis(uri = "redis://localhost:6379", streamKey = "events", maxlen = 0)
        }
    }

    @Test
    fun `negative maxlen is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Redis(uri = "redis://localhost:6379", streamKey = "events", maxlen = -1)
        }
    }
}
