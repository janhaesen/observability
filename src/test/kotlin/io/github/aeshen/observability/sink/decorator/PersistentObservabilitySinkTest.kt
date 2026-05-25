package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.impl.JsonLineCodec
import io.github.aeshen.observability.key.BooleanKey
import io.github.aeshen.observability.key.LongKey
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PersistentObservabilitySinkTest {
    @Test
    fun `replays persisted events after restart`() {
        val directory = Files.createTempDirectory("obs-persistent-replay")
        val first =
            PersistentObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) = error("delegate-down")
                    },
                directory = directory,
                retryDelayMillis = 10,
                closeTimeoutMillis = 25,
            )

        first.handle(sampleEncoded(message = "restart-me"))
        Thread.sleep(40)
        first.close()

        val replayed = mutableListOf<EncodedEvent>()
        val delivered = CountDownLatch(1)
        PersistentObservabilitySink(
            delegate = PersistentCapturingSink(replayed, delivered),
            directory = directory,
            retryDelayMillis = 10,
            closeTimeoutMillis = 200,
        ).use { sink ->
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals("restart-me", replayed.single().original.message)
            sink.close()
        }
    }

    @Test
    fun `does not replay acknowledged events on later restart`() {
        val directory = Files.createTempDirectory("obs-persistent-ack")
        val delivered = CountDownLatch(1)
        PersistentObservabilitySink(
            delegate = PersistentCapturingSink(mutableListOf(), delivered),
            directory = directory,
            retryDelayMillis = 10,
            closeTimeoutMillis = 200,
        ).use { sink ->
            sink.handle(sampleEncoded(message = "deliver-once"))
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
        }

        val replayed = CountDownLatch(1)
        PersistentObservabilitySink(
            delegate =
                object : ObservabilitySink {
                    override fun handle(event: EncodedEvent) {
                        replayed.countDown()
                    }
                },
            directory = directory,
            retryDelayMillis = 10,
            closeTimeoutMillis = 50,
        ).use {
            assertFalse(replayed.await(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `replay preserves encoded bytes scalar context metadata and error details`() {
        val directory = Files.createTempDirectory("obs-persistent-shape")
        val initial =
            PersistentObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) {
                            error("still-down")
                        }
                    },
                directory = directory,
                retryDelayMillis = 10,
                closeTimeoutMillis = 25,
            )
        val source = sampleEncoded()

        initial.handle(source)
        Thread.sleep(40)
        initial.close()

        val replayed = Collections.synchronizedList(mutableListOf<EncodedEvent>())
        val delivered = CountDownLatch(1)
        PersistentObservabilitySink(
            delegate = PersistentCapturingSink(replayed, delivered),
            directory = directory,
            retryDelayMillis = 10,
            closeTimeoutMillis = 200,
        ).use {
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
        }

        val restored = replayed.single()
        assertTrue(restored.encoded.contentEquals(source.encoded))
        assertEquals("TEST", restored.original.name.name)
        assertEquals("durable.test", restored.original.name.eventName)
        assertEquals("durable event", restored.original.message)
        assertEquals(201, restored.original.context.get(LongKey.STATUS_CODE))
        assertEquals("req-123", restored.original.context.get(StringKey.REQUEST_ID))
        assertEquals(true, restored.original.context.get(BooleanKey.SUCCESS))
        assertEquals(42L, restored.metadata["attempt"])
        assertEquals("ingest", restored.metadata["source"])
        val replayedError = assertIs<ReplayedObservabilityException>(restored.original.error)
        assertEquals(IllegalArgumentException::class.qualifiedName, replayedError.originalClassName)
        assertEquals("boom", replayedError.originalMessage)
    }

    @Test
    fun `rejects unsupported scalar values`() {
        val directory = Files.createTempDirectory("obs-persistent-invalid")
        PersistentObservabilitySink(
            delegate = NoopSink,
            directory = directory,
        ).use { sink ->
            assertFailsWith<IllegalArgumentException> {
                sink.handle(sampleEncoded(metadata = mutableMapOf("bad" to listOf("x"))))
            }
        }
    }

    @Test
    fun `fails when pending retention bounds are exceeded`() {
        val directory = Files.createTempDirectory("obs-persistent-bounds")
        PersistentObservabilitySink(
            delegate =
                object : ObservabilitySink {
                    override fun handle(event: EncodedEvent) = error("delegate-down")
                },
            directory = directory,
            maxPendingEvents = 1,
            retryDelayMillis = 10,
            closeTimeoutMillis = 25,
        ).use { sink ->
            sink.handle(sampleEncoded(message = "first"))
            assertFailsWith<IllegalStateException> {
                sink.handle(sampleEncoded(message = "second"))
            }
        }
    }

    @Test
    fun `truncates incomplete tail and replays intact records`() {
        val directory = Files.createTempDirectory("obs-persistent-tail")
        val initial =
            PersistentObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) = error("delegate-down")
                    },
                directory = directory,
                retryDelayMillis = 10,
                closeTimeoutMillis = 25,
            )
        initial.handle(sampleEncoded(message = "keep-me"))
        Thread.sleep(40)
        initial.close()

        Files.write(
            directory.resolve("persistent-buffer.journal"),
            byteArrayOf(0x01, 0x02, 0x03),
            StandardOpenOption.APPEND,
        )

        val replayed = mutableListOf<EncodedEvent>()
        val delivered = CountDownLatch(1)
        PersistentObservabilitySink(
            delegate = PersistentCapturingSink(replayed, delivered),
            directory = directory,
            retryDelayMillis = 10,
            closeTimeoutMillis = 200,
        ).use {
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals("keep-me", replayed.single().original.message)
        }
    }
}

private object NoopSink : ObservabilitySink {
    override fun handle(event: EncodedEvent) = Unit
}

private class PersistentCapturingSink(
    private val seen: MutableList<EncodedEvent>,
    private val latch: CountDownLatch,
) : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        seen += event
        latch.countDown()
    }
}

private fun sampleEncoded(
    message: String = "durable event",
    metadata: MutableMap<String, Any?> = mutableMapOf("attempt" to 42L, "source" to "ingest"),
): EncodedEvent {
    val context =
        ObservabilityContext
            .builder()
            .put(StringKey.REQUEST_ID, "req-123")
            .put(LongKey.STATUS_CODE, 201L)
            .put(BooleanKey.SUCCESS, true)
            .build()

    val event =
        ObservabilityEvent.EventBuilder(PersistentEvent.TEST)
            .level(EventLevel.WARN)
            .message(message)
            .payload("payload".toByteArray(Charsets.UTF_8))
            .context(context)
            .error(IllegalArgumentException("boom"))
            .build()

    return JsonLineCodec().encode(event).apply {
        this.metadata.putAll(metadata)
    }
}

private enum class PersistentEvent(
    override val eventName: String? = null,
) : EventName {
    TEST("durable.test"),
}
