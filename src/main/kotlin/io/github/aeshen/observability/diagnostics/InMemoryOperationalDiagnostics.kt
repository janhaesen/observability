package io.github.aeshen.observability.diagnostics

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight, lock-free diagnostics collector for operational metrics and health snapshots.
 */
@Suppress("TooManyFunctions", "detekt.TooManyFunctions")
class InMemoryOperationalDiagnostics : ObservabilityDiagnostics {
    private val sinkHandleErrors = AtomicLong(0)
    private val sinkCloseErrors = AtomicLong(0)
    private val asyncDrops = AtomicLong(0)
    private val asyncWorkerErrors = AtomicLong(0)
    private val retryExhaustions = AtomicLong(0)
    private val batchFlushSuccesses = AtomicLong(0)
    private val batchFlushFailures = AtomicLong(0)
    private val batchFlushTotalCount = AtomicLong(0)
    private val batchFlushTotalElapsedMillis = AtomicLong(0)
    private val batchFlushedEvents = AtomicLong(0)

    private val asyncQueueDepth = AtomicInteger(0)
    private val asyncQueueDepthMax = AtomicInteger(0)
    private val asyncQueueCapacity = AtomicInteger(0)

    private val asyncWorkerHealthy = AtomicBoolean(true)
    private val asyncWorkerMessage = AtomicReference<String?>(null)

    override fun onSinkHandleError(
        sink: ObservabilitySink,
        event: EncodedEvent,
        error: Exception,
    ) {
        sinkHandleErrors.incrementAndGet()
    }

    override fun onSinkCloseError(
        sink: ObservabilitySink,
        error: Exception,
    ) {
        sinkCloseErrors.incrementAndGet()
    }

    override fun onAsyncDrop(
        event: EncodedEvent,
        reason: String,
    ) {
        asyncDrops.incrementAndGet()
    }

    override fun onAsyncWorkerError(error: Exception) {
        asyncWorkerErrors.incrementAndGet()
        asyncWorkerHealthy.set(false)
        asyncWorkerMessage.set(error.message)
    }

    override fun onAsyncQueueDepth(
        queueDepth: Int,
        capacity: Int,
    ) {
        asyncQueueDepth.set(queueDepth)
        asyncQueueCapacity.set(capacity)
        asyncQueueDepthMax.accumulateAndGet(queueDepth) { current, incoming ->
            if (incoming > current) incoming else current
        }
    }

    override fun onAsyncWorkerState(
        healthy: Boolean,
        message: String?,
    ) {
        asyncWorkerHealthy.set(healthy)
        asyncWorkerMessage.set(message)
    }

    override fun onBatchFlush(
        batchSize: Int,
        elapsedMillis: Long,
        success: Boolean,
        error: Exception?,
    ) {
        batchFlushTotalCount.incrementAndGet()
        batchFlushTotalElapsedMillis.addAndGet(elapsedMillis)
        batchFlushedEvents.addAndGet(batchSize.toLong())
        if (success) {
            batchFlushSuccesses.incrementAndGet()
        } else {
            batchFlushFailures.incrementAndGet()
        }
    }

    override fun onRetryExhaustion(
        event: EncodedEvent,
        attempts: Int,
        lastError: Exception,
    ) {
        retryExhaustions.incrementAndGet()
    }

    fun metricsSnapshot(): OperationalMetricsSnapshot =
        OperationalMetricsSnapshot(
            sinkHandleErrors = sinkHandleErrors.get(),
            sinkCloseErrors = sinkCloseErrors.get(),
            asyncDrops = asyncDrops.get(),
            asyncWorkerErrors = asyncWorkerErrors.get(),
            retryExhaustions = retryExhaustions.get(),
            batchFlushSuccesses = batchFlushSuccesses.get(),
            batchFlushFailures = batchFlushFailures.get(),
            batchFlushTotalCount = batchFlushTotalCount.get(),
            batchFlushTotalElapsedMillis = batchFlushTotalElapsedMillis.get(),
            batchFlushedEvents = batchFlushedEvents.get(),
            asyncQueueDepth = asyncQueueDepth.get(),
            asyncQueueDepthMax = asyncQueueDepthMax.get(),
            asyncQueueCapacity = asyncQueueCapacity.get(),
        )

    fun healthSnapshot(): OperationalHealthSnapshot {
        val status =
            when {
                !asyncWorkerHealthy.get() -> HealthStatus.UNHEALTHY
                hasReliabilitySignals() -> HealthStatus.DEGRADED
                else -> HealthStatus.READY
            }

        return OperationalHealthSnapshot(
            status = status,
            ready = status != HealthStatus.UNHEALTHY,
            asyncWorkerHealthy = asyncWorkerHealthy.get(),
            asyncWorkerMessage = asyncWorkerMessage.get(),
        )
    }

    private fun hasReliabilitySignals(): Boolean =
        sinkHandleErrors.get() > 0 ||
            sinkCloseErrors.get() > 0 ||
            asyncDrops.get() > 0 ||
            retryExhaustions.get() > 0 ||
            batchFlushFailures.get() > 0
}

enum class HealthStatus {
    READY,
    DEGRADED,
    UNHEALTHY,
}

data class OperationalHealthSnapshot(
    val status: HealthStatus,
    val ready: Boolean,
    val asyncWorkerHealthy: Boolean,
    val asyncWorkerMessage: String?,
)

data class OperationalMetricsSnapshot(
    val sinkHandleErrors: Long,
    val sinkCloseErrors: Long,
    val asyncDrops: Long,
    val asyncWorkerErrors: Long,
    val retryExhaustions: Long,
    val batchFlushSuccesses: Long,
    val batchFlushFailures: Long,
    val batchFlushTotalCount: Long,
    val batchFlushTotalElapsedMillis: Long,
    val batchFlushedEvents: Long,
    val asyncQueueDepth: Int,
    val asyncQueueDepthMax: Int,
    val asyncQueueCapacity: Int,
) {
    val averageBatchFlushMillis: Double =
        if (batchFlushTotalCount == 0L) {
            0.0
        } else {
            batchFlushTotalElapsedMillis.toDouble() / batchFlushTotalCount.toDouble()
        }
}
