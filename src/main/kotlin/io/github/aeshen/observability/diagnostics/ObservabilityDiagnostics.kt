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
    ) = Unit

    fun onSinkCloseError(
        sink: ObservabilitySink,
        error: Exception,
    ) = Unit

    fun onAsyncDrop(
        event: EncodedEvent,
        reason: String,
    ) = Unit

    fun onAsyncWorkerError(error: Exception) = Unit

    fun onAsyncQueueDepth(
        queueDepth: Int,
        capacity: Int,
    ) = Unit

    fun onAsyncWorkerState(
        healthy: Boolean,
        message: String? = null,
    ) = Unit

    fun onBatchFlush(
        batchSize: Int,
        elapsedMillis: Long,
        success: Boolean,
        error: Exception?,
    ) = Unit

    fun onRetryExhaustion(
        event: EncodedEvent,
        attempts: Int,
        lastError: Exception,
    ) = Unit

    companion object {
        val NOOP: ObservabilityDiagnostics = object : ObservabilityDiagnostics {}
    }
}
