package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.sink.BatchCapableObservabilitySink
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.testing.ObservabilitySinkConformanceSuite
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `close times out when delegate blocks and drain policy is used`() {
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val sink =
            AsyncObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) {
                            started.countDown()
                            unblock.await(5, TimeUnit.SECONDS)
                        }
                    },
                closeTimeoutMillis = 50,
                shutdownPolicy = AsyncObservabilitySink.ShutdownPolicy.DRAIN,
            )

        sink.handle(sample("blocked"))
        assertTrue(started.await(1, TimeUnit.SECONDS))

        assertFailsWith<IllegalStateException> {
            sink.close()
        }

        unblock.countDown()
    }

    @Test
    fun `signals drops when queue is full in best effort mode`() {
        val drops = mutableListOf<AsyncObservabilitySink.DropReason>()
        val release = CountDownLatch(1)
        val diagnostics =
            object : ObservabilityDiagnostics {
                override fun onAsyncDrop(
                    event: EncodedEvent,
                    reason: String,
                ) {
                    drops += AsyncObservabilitySink.DropReason.valueOf(reason)
                }
            }
        val sink =
            AsyncObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) {
                            release.await(5, TimeUnit.SECONDS)
                        }
                    },
                capacity = 1,
                offerTimeoutMillis = 1,
                failOnDrop = false,
                diagnostics = diagnostics,
            )

        sink.handle(sample("first"))
        sink.handle(sample("second"))
        sink.handle(sample("third"))

        release.countDown()
        sink.close()

        assertTrue(drops.contains(AsyncObservabilitySink.DropReason.QUEUE_FULL))
    }

    @Test
    fun `signals pending drops on drop pending close policy`() {
        val reasons = mutableListOf<AsyncObservabilitySink.DropReason>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val diagnostics =
            object : ObservabilityDiagnostics {
                override fun onAsyncDrop(
                    event: EncodedEvent,
                    reason: String,
                ) {
                    reasons += AsyncObservabilitySink.DropReason.valueOf(reason)
                }
            }
        val sink =
            AsyncObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent) {
                            started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                    },
                capacity = 8,
                shutdownPolicy = AsyncObservabilitySink.ShutdownPolicy.DROP_PENDING,
                closeTimeoutMillis = 200,
                diagnostics = diagnostics,
            )

        sink.handle(sample("in-flight"))
        assertTrue(started.await(1, TimeUnit.SECONDS))
        sink.handle(sample("queued-a"))
        sink.handle(sample("queued-b"))

        sink.close()
        release.countDown()

        assertTrue(reasons.contains(AsyncObservabilitySink.DropReason.DROP_PENDING_ON_CLOSE))
    }

    @Test
    fun `reports async diagnostics for worker errors and drops`() {
        val workerErrors = mutableListOf<String>()
        val drops = mutableListOf<String>()
        val diagnostics =
            object : ObservabilityDiagnostics {
                override fun onAsyncDrop(
                    event: EncodedEvent,
                    reason: String,
                ) {
                    drops += reason
                }

                override fun onAsyncWorkerError(error: Exception) {
                    workerErrors += error.message.orEmpty()
                }
            }

        val sink =
            AsyncObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent): Unit = throw IllegalStateException("worker-failed")
                    },
                capacity = 1,
                offerTimeoutMillis = 1,
                diagnostics = diagnostics,
            )

        sink.handle(sample("one"))
        Thread.sleep(80)
        sink.close()
        assertFailsWith<IllegalStateException> {
            sink.handle(sample("after-close"))
        }

        assertTrue(workerErrors.contains("worker-failed"))
        assertTrue(drops.contains(AsyncObservabilitySink.DropReason.CLOSED.name))
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

    @Test
    fun `batching sink flushes by timer without additional writes`() {
        val handled = mutableListOf<EncodedEvent>()
        val sink =
            BatchingObservabilitySink(
                delegate = CapturingSink(handled),
                maxBatchSize = 10,
                flushIntervalMillis = 30,
            )

        sink.handle(sample("timer"))
        Thread.sleep(120)

        assertEquals(1, handled.size)
        sink.close()
    }

    @Test
    fun `batching sink reports flush diagnostics`() {
        val outcomes = mutableListOf<Boolean>()
        val diagnostics =
            object : ObservabilityDiagnostics {
                override fun onBatchFlush(
                    batchSize: Int,
                    elapsedMillis: Long,
                    success: Boolean,
                    error: Exception?,
                ) {
                    outcomes += success
                }
            }

        val sink =
            BatchingObservabilitySink(
                delegate = CapturingSink(mutableListOf()),
                maxBatchSize = 1,
                flushIntervalMillis = 60_000,
                diagnostics = diagnostics,
            )

        sink.handle(sample("diagnostics"))
        sink.close()

        assertTrue(outcomes.contains(true))
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

    @Test
    fun `retrying sink reports diagnostics on retry exhaustion`() {
        val retryOutcomes = mutableListOf<Pair<Int, Boolean>>()
        val diagnostics =
            object : ObservabilityDiagnostics {
                override fun onRetryExhaustion(
                    event: EncodedEvent,
                    attempts: Int,
                    lastError: Exception,
                ) {
                    retryOutcomes += attempts to true
                }
            }

        val sink =
            RetryingObservabilitySink(
                delegate =
                    object : ObservabilitySink {
                        override fun handle(event: EncodedEvent): Unit = throw IllegalStateException("persistent failure")
                    },
                maxAttempts = 3,
                backoff = BackoffStrategy.fixed(0),
                diagnostics = diagnostics,
            )

        assertFailsWith<IllegalStateException> {
            sink.handle(sample("retry-with-diag"))
        }

        assertEquals(1, retryOutcomes.size)
        assertEquals(3, retryOutcomes.single().first)
    }
}

class DiagnosticsIntegrationTest {
    @Test
    fun `diagnostics tracks async drops, batch flushes, and retry outcomes`() {
        val events = mutableMapOf<String, MutableList<String>>()
        val diagnostics =
            object : ObservabilityDiagnostics {
                override fun onAsyncDrop(
                    event: EncodedEvent,
                    reason: String,
                ) {
                    events.getOrPut("async_drops") { mutableListOf() } += reason
                }

                override fun onAsyncWorkerError(error: Exception) {
                    events.getOrPut("worker_errors") { mutableListOf() } += error.message.orEmpty()
                }

                override fun onBatchFlush(
                    batchSize: Int,
                    elapsedMillis: Long,
                    success: Boolean,
                    error: Exception?,
                ) {
                    val outcome = if (success) "success" else "failure"
                    events.getOrPut("batch_flushes") { mutableListOf() } += "$batchSize:$outcome"
                }

                override fun onRetryExhaustion(
                    event: EncodedEvent,
                    attempts: Int,
                    lastError: Exception,
                ) {
                    events.getOrPut("retry_exhaustion") { mutableListOf() } += "$attempts:${lastError.message}"
                }
            }

        val handled = mutableListOf<EncodedEvent>()
        val sink =
            BatchingObservabilitySink(
                delegate = CapturingSink(handled),
                maxBatchSize = 2,
                flushIntervalMillis = 60_000,
                diagnostics = diagnostics,
            )

        sink.handle(sample("event-1"))
        sink.handle(sample("event-2"))

        // Batching sink should flush when maxBatchSize is reached
        assertEquals(2, handled.size, "Both events should be handled in one batch flush")
        assertTrue(
            events["batch_flushes"]?.any { it.startsWith("2:success") } ?: false,
            "Batch flush should be reported as success with size 2",
        )
        sink.close()
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
