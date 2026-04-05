package io.github.aeshen.observability.config.sink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class S3ConfigTest {
    @Test
    fun `valid config constructs successfully`() {
        val config = S3(bucket = "my-bucket", region = "eu-west-1")
        assertEquals("my-bucket", config.bucket)
        assertEquals("eu-west-1", config.region)
        assertEquals("observability/", config.keyPrefix)
        assertNull(config.endpoint)
        assertEquals(30_000L, config.timeoutMillis)
    }

    @Test
    fun `custom endpoint is accepted`() {
        val config = S3(bucket = "bucket", region = "us-east-1", endpoint = "http://localhost:9000")
        assertEquals("http://localhost:9000", config.endpoint)
    }

    @Test
    fun `blank bucket is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            S3(bucket = " ", region = "eu-west-1")
        }
    }

    @Test
    fun `blank region is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            S3(bucket = "my-bucket", region = "")
        }
    }

    @Test
    fun `blank keyPrefix is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            S3(bucket = "my-bucket", region = "eu-west-1", keyPrefix = "  ")
        }
    }

    @Test
    fun `zero timeoutMillis is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            S3(bucket = "my-bucket", region = "eu-west-1", timeoutMillis = 0)
        }
    }

    @Test
    fun `relative endpoint is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            S3(bucket = "my-bucket", region = "eu-west-1", endpoint = "not-a-uri")
        }
    }
}
