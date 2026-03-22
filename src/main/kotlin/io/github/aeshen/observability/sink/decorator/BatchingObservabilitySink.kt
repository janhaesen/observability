package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.sink.BatchCapableObservabilitySink
import io.github.aeshen.observability.sink.ObservabilitySink
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decorator that buffers records and flushes on size or interval.
 */
class BatchingObservabilitySink(
    private val delegate: ObservabilitySink,
    private val maxBatchSize: Int = 50,
    flushIntervalMillis: Long = 1000,
    private val diagnostics: ObservabilityDiagnostics = ObservabilityDiagnostics.NOOP,
) : ObservabilitySink {
    private val lock = Any()
    private val flushLock = Any()
    private val accepting = AtomicBoolean(true)
    private val buffer = mutableListOf<EncodedEvent>()
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "observability-batching-flusher").apply { isDaemon = true }
        }

    init {
        require(maxBatchSize > 0) { "maxBatchSize must be greater than 0." }
        require(flushIntervalMillis > 0) { "flushIntervalMillis must be greater than 0." }

        scheduler.scheduleAtFixedRate(
            { flushFromTimer() },
            flushIntervalMillis,
            flushIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun handle(event: EncodedEvent) {
        check(accepting.get()) { "BatchingObservabilitySink is closed." }

        var readyToFlush: List<EncodedEvent>? = null
        synchronized(lock) {
            buffer += event.copy(metadata = event.metadata.toMutableMap())
            if (buffer.size >= maxBatchSize) {
                readyToFlush = drainLocked()
            }
        }

        readyToFlush?.let { flush(it) }
    }

    override fun close() {
        accepting.set(false)
        scheduler.shutdownNow()

        val remaining = synchronized(lock) { drainLocked() }
        if (remaining.isNotEmpty()) {
            flush(remaining)
        }
        delegate.close()
    }

    private fun drainLocked(): List<EncodedEvent> {
        if (buffer.isEmpty()) {
            return emptyList()
        }

        val snapshot = Collections.unmodifiableList(buffer.toList())
        buffer.clear()
        return snapshot
    }

    private fun flushFromTimer() {
        if (!accepting.get()) {
            return
        }

        val batch = synchronized(lock) { drainLocked() }
        if (batch.isNotEmpty()) {
            flush(batch)
        }
    }

    private fun flush(batch: List<EncodedEvent>) {
        if (batch.isEmpty()) return

        val startedAt = System.nanoTime()
        synchronized(flushLock) {
            try {
                if (delegate is BatchCapableObservabilitySink) {
                    delegate.handleBatch(batch)
                } else {
                    batch.forEach { delegate.handle(it) }
                }

                diagnostics.onBatchFlush(
                    batchSize = batch.size,
                    elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                    success = true,
                    error = null,
                )
            } catch (e: IllegalArgumentException) {
                diagnostics.onBatchFlush(
                    batchSize = batch.size,
                    elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                    success = false,
                    error = e,
                )
                throw e
            } catch (e: IllegalStateException) {
                diagnostics.onBatchFlush(
                    batchSize = batch.size,
                    elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                    success = false,
                    error = e,
                )
                throw e
            }
        }
    }
}
