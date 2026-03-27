package io.github.aeshen.observability.config.sink

import kotlin.test.Test
import kotlin.test.assertFailsWith

class HttpConfigTest {
    @Test
    fun `rejects invalid endpoint and timeout`() {
        assertFailsWith<IllegalArgumentException> {
            Http(endpoint = "")
        }
        assertFailsWith<IllegalArgumentException> {
            Http(endpoint = "/relative")
        }
        assertFailsWith<IllegalArgumentException> {
            Http(endpoint = "ftp://example.com/ingest")
        }
        assertFailsWith<IllegalArgumentException> {
            Http(endpoint = "https://example.com/ingest", timeoutMillis = 0)
        }
    }

    @Test
    fun `rejects blank header names`() {
        assertFailsWith<IllegalArgumentException> {
            Http(
                endpoint = "https://example.com/ingest",
                headers = mapOf("" to "value"),
            )
        }
    }
}
