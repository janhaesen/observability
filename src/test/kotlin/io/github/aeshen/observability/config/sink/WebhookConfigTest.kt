package io.github.aeshen.observability.config.sink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebhookConfigTest {
    @Test
    fun `valid config constructs successfully`() {
        val config = Webhook(endpoint = "https://hooks.example.com/events", secret = "s3cr3t")
        assertEquals("https://hooks.example.com/events", config.endpoint)
        assertEquals("s3cr3t", config.secret)
        assertEquals("X-Hub-Signature-256", config.signatureHeader)
        assertEquals(emptyMap(), config.headers)
        assertEquals(5_000L, config.timeoutMillis)
    }

    @Test
    fun `blank endpoint is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Webhook(endpoint = "  ", secret = "s3cr3t")
        }
    }

    @Test
    fun `non-http endpoint scheme is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Webhook(endpoint = "ftp://example.com/hook", secret = "s3cr3t")
        }
    }

    @Test
    fun `blank secret is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Webhook(endpoint = "https://hooks.example.com/events", secret = "")
        }
    }

    @Test
    fun `blank signatureHeader is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Webhook(endpoint = "https://hooks.example.com/events", secret = "s3cr3t", signatureHeader = "  ")
        }
    }

    @Test
    fun `zero timeoutMillis is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Webhook(endpoint = "https://hooks.example.com/events", secret = "s3cr3t", timeoutMillis = 0)
        }
    }

    @Test
    fun `blank header key is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Webhook(
                endpoint = "https://hooks.example.com/events",
                secret = "s3cr3t",
                headers = mapOf("" to "value"),
            )
        }
    }
}
