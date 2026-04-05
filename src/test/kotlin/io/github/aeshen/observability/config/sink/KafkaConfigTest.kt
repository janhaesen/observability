package io.github.aeshen.observability.config.sink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KafkaConfigTest {
    @Test
    fun `valid config constructs successfully`() {
        val config = Kafka(bootstrapServers = "localhost:9092", topic = "events")
        assertEquals("localhost:9092", config.bootstrapServers)
        assertEquals("events", config.topic)
        assertEquals("observability-sink", config.clientId)
        assertEquals(emptyMap(), config.additionalProperties)
        assertEquals(5_000L, config.timeoutMillis)
    }

    @Test
    fun `blank bootstrapServers is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Kafka(bootstrapServers = "  ", topic = "events")
        }
    }

    @Test
    fun `blank topic is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Kafka(bootstrapServers = "localhost:9092", topic = "")
        }
    }

    @Test
    fun `blank clientId is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Kafka(bootstrapServers = "localhost:9092", topic = "events", clientId = "  ")
        }
    }

    @Test
    fun `zero timeoutMillis is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Kafka(bootstrapServers = "localhost:9092", topic = "events", timeoutMillis = 0)
        }
    }

    @Test
    fun `negative timeoutMillis is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Kafka(bootstrapServers = "localhost:9092", topic = "events", timeoutMillis = -1)
        }
    }

    @Test
    fun `additionalProperties are preserved`() {
        val props = mapOf("security.protocol" to "SASL_SSL", "sasl.mechanism" to "PLAIN")
        val config = Kafka(bootstrapServers = "broker:9093", topic = "secure-events", additionalProperties = props)
        assertEquals(props, config.additionalProperties)
    }
}
