package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.BatchCapableObservabilitySink
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.testing.ObservabilitySinkConformanceSuite
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AsyncObservabilitySinkConformanceTest : ObservabilitySinkConformanceSuite() {
    private val observed = mutableListOf<EncodedEvent>()

    override fun createSubjectSink(): ObservabilitySink {
        observed.clear()
        return AsyncObservabilitySink(delegate = CapturingSink(observed))
    }

    override fun observedEvents(): List<EncodedEvent> = observed.toList()

    @Test
    fun `sink forwards handled event bytes and metadata`() {
        assertForwardsHandledEventBytesAndMetadata()
    }

    @Test
    fun `close can be called repeatedly`() {
        assertCloseCanBeCalledRepeatedly()
    }

    @Test
    fun `concurrent handle calls are safe`() {
        assertConcurrentHandleSafety()
    }

    @Test
    fun `close rejects writes deterministically`() {
        assertCloseRejectsFurtherWritesDeterministically()
    }

    @Test
    fun `error mode contract helper behaves deterministically`() {
        assertHandleErrorModeContract()
    }
}

class BatchingObservabilitySinkTest {
    @Test
    fun `batching sink flushes full batch to batch-capable delegate`() {
        val batches = mutableListOf<List<EncodedEvent>>()
        val sink =
            BatchingObservabilitySink(
                delegate = RecordingBatchSink(batches),
                maxBatchSize = 2,
                flushIntervalMillis = 60_000,
            )

        sink.handle(sample("one"))
        sink.handle(sample("two"))

        assertEquals(1, batches.size)
        assertEquals(listOf("one", "two"), batches.single().map { it.encoded.toString(Charsets.UTF_8) })
        sink.close()
    }

    @Test
    fun `batching sink flushes pending events on close`() {
        val handled = mutableListOf<EncodedEvent>()
        val sink =
            BatchingObservabilitySink(
                delegate = CapturingSink(handled),
                maxBatchSize = 10,
                flushIntervalMillis = 60_000,
            )

        sink.handle(sample("pending"))
        assertEquals(0, handled.size)

        sink.close()
        assertEquals(1, handled.size)
    }

    private class RecordingBatchSink(
        private val batches: MutableList<List<EncodedEvent>>,
    ) : BatchCapableObservabilitySink {
        override fun handle(event: EncodedEvent) {
            batches += listOf(event)
        }

        override fun handleBatch(events: List<EncodedEvent>) {
            batches += events.map { it.copy(metadata = it.metadata.toMutableMap()) }
        }
    }
}

class RetryingObservabilitySinkTest {
    @Test
    fun `retrying sink eventually succeeds before max attempts`() {
        val attempts = AtomicInteger(0)
        val sink =
            RetryingObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) {
                            if (attempts.incrementAndGet() < 3) {
                                error("transient")
                            }
                        }
                    },
                maxAttempts = 5,
                backoff = BackoffStrategy.fixed(0),
            )

        sink.handle(sample("retry"))
        assertEquals(3, attempts.get())
    }

    @Test
    fun `retrying sink throws after exhausting attempts`() {
        val sink =
            RetryingObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) {
                            error("always failing")
                        }
                    },
                maxAttempts = 2,
                backoff = BackoffStrategy.fixed(0),
            )

        assertFailsWith<IllegalStateException> {
            sink.handle(sample("retry-fail"))
        }
    }
}

private class CapturingSink(
    private val seen: MutableList<EncodedEvent>,
) : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        seen += event.copy(metadata = event.metadata.toMutableMap())
    }
}

private fun sample(payload: String): EncodedEvent =
    EncodedEvent(
        original =
            ObservabilityEvent(
                name = DecoratorEvent.TEST,
                level = EventLevel.INFO,
                context = ObservabilityContext.empty(),
            ),
        encoded = payload.toByteArray(Charsets.UTF_8),
    )

private enum class DecoratorEvent(
    override val eventName: String? = null,
) : EventName {
    TEST("sink.decorator.test"),
}
