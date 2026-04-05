package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Kafka
import io.github.aeshen.observability.sink.ObservabilitySink
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DEFAULT_EVENT_KEY = "observability"

internal class KafkaObservabilitySink internal constructor(
    private val producer: Producer<String, ByteArray>,
    private val topic: String,
    private val timeoutMillis: Long,
) : ObservabilitySink {
    constructor(config: Kafka) : this(
        producer = buildProducer(config),
        topic = config.topic,
        timeoutMillis = config.timeoutMillis,
    )

    override fun handle(event: EncodedEvent) {
        val key = event.metadata["event"] as? String ?: DEFAULT_EVENT_KEY
        val record = ProducerRecord(topic, key, event.encoded)
        try {
            producer.send(record).get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw IllegalStateException("Kafka sink failed to send record to topic=$topic.", e.cause ?: e)
        } catch (e: TimeoutException) {
            throw IllegalStateException(
                "Kafka sink timed out sending record to topic=$topic after ${timeoutMillis}ms.",
                e,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Kafka sink send interrupted for topic=$topic.", e)
        }
    }

    override fun close() {
        producer.close(Duration.ofMillis(timeoutMillis))
    }
}

private fun buildProducer(config: Kafka): KafkaProducer<String, ByteArray> {
    val props = Properties()
    props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = config.bootstrapServers
    props[ProducerConfig.CLIENT_ID_CONFIG] = config.clientId
    props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
    props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java.name
    config.additionalProperties.forEach { (k, v) -> props[k] = v }
    return KafkaProducer(props)
}
