package io.github.aeshen.observability.diagnostics

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryOperationalDiagnosticsTest {
    @Test
    fun `collector captures metrics snapshot from diagnostics hooks`() {
        val diagnostics = InMemoryOperationalDiagnostics()

        diagnostics.onSinkHandleError(NoopSink, sampleEvent(), IllegalStateException("handle"))
        diagnostics.onSinkCloseError(NoopSink, IllegalStateException("close"))
        diagnostics.onAsyncDrop(sampleEvent(), "QUEUE_FULL")
        diagnostics.onAsyncQueueDepth(queueDepth = 3, capacity = 8)
        diagnostics.onBatchFlush(batchSize = 2, elapsedMillis = 12, success = true, error = null)
        diagnostics.onBatchFlush(
            batchSize = 1,
            elapsedMillis = 8,
            success = false,
            error = IllegalStateException("flush"),
        )
        diagnostics.onRetryExhaustion(
            sampleEvent(),
            attempts = 3,
            lastError = IllegalStateException("retry"),
        )

        val snapshot = diagnostics.metricsSnapshot()

        assertEquals(1, snapshot.sinkHandleErrors)
        assertEquals(1, snapshot.sinkCloseErrors)
        assertEquals(1, snapshot.asyncDrops)
        assertEquals(1, snapshot.retryExhaustions)
        assertEquals(1, snapshot.batchFlushSuccesses)
        assertEquals(1, snapshot.batchFlushFailures)
        assertEquals(2, snapshot.batchFlushTotalCount)
        assertEquals(20, snapshot.batchFlushTotalElapsedMillis)
        assertEquals(3, snapshot.batchFlushedEvents)
        assertEquals(3, snapshot.asyncQueueDepth)
        assertEquals(3, snapshot.asyncQueueDepthMax)
        assertEquals(8, snapshot.asyncQueueCapacity)
        assertEquals(10.0, snapshot.averageBatchFlushMillis)
    }

    @Test
    fun `collector health is degraded when reliability signals are present`() {
        val diagnostics = InMemoryOperationalDiagnostics()

        diagnostics.onRetryExhaustion(sampleEvent(), attempts = 2, lastError = IllegalStateException("retry"))

        val health = diagnostics.healthSnapshot()

        assertEquals(HealthStatus.DEGRADED, health.status)
        assertTrue(health.ready)
        assertTrue(health.asyncWorkerHealthy)
    }

    @Test
    fun `collector health is unhealthy when async worker reports failure`() {
        val diagnostics = InMemoryOperationalDiagnostics()

        diagnostics.onAsyncWorkerState(healthy = false, message = "worker failed")

        val health = diagnostics.healthSnapshot()

        assertEquals(HealthStatus.UNHEALTHY, health.status)
        assertFalse(health.ready)
        assertEquals("worker failed", health.asyncWorkerMessage)
    }

    private fun sampleEvent(): EncodedEvent =
        EncodedEvent(
            original =
                ObservabilityEvent(
                    name = TestEvent.TEST,
                    level = EventLevel.INFO,
                    context = ObservabilityContext.empty(),
                ),
            encoded = "payload".toByteArray(Charsets.UTF_8),
        )

    private object NoopSink : ObservabilitySink {
        override fun handle(event: EncodedEvent) = Unit
    }

    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("diagnostics.test"),
    }
}
