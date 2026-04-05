package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Redis
import io.github.aeshen.observability.sink.ObservabilitySink
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.time.toJavaDuration

private const val DEFAULT_EVENT_KEY = "observability"

internal class RedisObservabilitySink internal constructor(
    private val publisher: RedisPublisher,
    private val closeAction: (() -> Unit)? = null,
) : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        val eventName = event.metadata["event"] as? String ?: DEFAULT_EVENT_KEY
        val payload = event.encoded.toString(Charsets.UTF_8)
        publisher.publish(eventName, payload)
    }

    override fun close() {
        closeAction?.invoke()
    }

    companion object {
        fun fromConfig(config: Redis): RedisObservabilitySink {
            val redisUri = RedisURI.create(config.uri)
            redisUri.timeout = config.timeoutMillis.toDuration(DurationUnit.MILLISECONDS).toJavaDuration()
            val client = RedisClient.create(redisUri)
            val xaddArgs = config.maxlen?.let { maxLen -> XAddArgs().maxlen(maxLen) }
            // Connection is established lazily on the first handle() call.
            var connection: StatefulRedisConnection<String, String>? = null

            return RedisObservabilitySink(
                publisher =
                    { eventName, payload ->
                        try {
                            val conn =
                                synchronized(client) {
                                    connection ?: client.connect().also { connection = it }
                                }
                            conn.sync().xadd(
                                config.streamKey,
                                xaddArgs ?: XAddArgs(),
                                mapOf("event" to eventName, "payload" to payload),
                            )
                        } catch (e: RedisException) {
                            throw IllegalStateException(
                                "Redis sink failed to publish to stream=${config.streamKey} on ${config.uri}.",
                                e,
                            )
                        }
                    },
                closeAction = {
                    synchronized(client) { connection?.close() }
                    client.shutdown()
                },
            )
        }
    }
}

internal fun interface RedisPublisher {
    fun publish(
        eventName: String,
        payload: String,
    )
}
