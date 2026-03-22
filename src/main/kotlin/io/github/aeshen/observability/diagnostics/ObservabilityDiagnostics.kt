package io.github.aeshen.observability.diagnostics

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink

/**
 * Internal runtime signals for operators to track reliability outcomes.
 */
interface ObservabilityDiagnostics {
    fun onSinkHandleError(
        sink: ObservabilitySink,
        event: EncodedEvent,
        error: Exception,
    ) {}

    fun onSinkCloseError(
        sink: ObservabilitySink,
        error: Exception,
    ) {}

    fun onAsyncDrop(
        event: EncodedEvent,
        reason: String,
    ) {}

    fun onAsyncWorkerError(error: Exception) {}

    fun onAsyncQueueDepth(
        queueDepth: Int,
        capacity: Int,
    ) {}

    fun onAsyncWorkerState(
        healthy: Boolean,
        message: String? = null,
    ) {}

    fun onBatchFlush(
        batchSize: Int,
        elapsedMillis: Long,
        success: Boolean,
        error: Exception?,
    ) {}

    fun onRetryExhaustion(
        event: EncodedEvent,
        attempts: Int,
        lastError: Exception,
    ) {}

    companion object {
        val NOOP: ObservabilityDiagnostics = object : ObservabilityDiagnostics {}
    }
}
