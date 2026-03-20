package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.BatchCapableObservabilitySink
import io.github.aeshen.observability.sink.ObservabilitySink
import java.util.Collections

/**
 * Decorator that buffers records and flushes on size or interval.
 */
class BatchingObservabilitySink(
    private val delegate: ObservabilitySink,
    private val maxBatchSize: Int = 50,
    private val flushIntervalMillis: Long = 1000,
) : ObservabilitySink {
    private val lock = Any()
    private val buffer = mutableListOf<EncodedEvent>()
    private var lastFlushMillis = System.currentTimeMillis()

    init {
        require(maxBatchSize > 0) { "maxBatchSize must be greater than 0." }
    }

    override fun handle(event: EncodedEvent) {
        var readyToFlush: List<EncodedEvent>? = null
        synchronized(lock) {
            buffer += event.copy(metadata = event.metadata.toMutableMap())
            val now = System.currentTimeMillis()
            val flushBySize = buffer.size >= maxBatchSize
            val flushByTime = now - lastFlushMillis >= flushIntervalMillis
            if (flushBySize || flushByTime) {
                readyToFlush = drainLocked(now)
            }
        }

        readyToFlush?.let { flush(it) }
    }

    override fun close() {
        val remaining = synchronized(lock) { drainLocked(System.currentTimeMillis()) }
        if (remaining.isNotEmpty()) {
            flush(remaining)
        }
        delegate.close()
    }

    private fun drainLocked(nowMillis: Long): List<EncodedEvent> {
        if (buffer.isEmpty()) {
            lastFlushMillis = nowMillis
            return emptyList()
        }

        val snapshot = Collections.unmodifiableList(buffer.toList())
        buffer.clear()
        lastFlushMillis = nowMillis
        return snapshot
    }

    private fun flush(batch: List<EncodedEvent>) {
        if (batch.isEmpty()) return

        if (delegate is BatchCapableObservabilitySink) {
            delegate.handleBatch(batch)
            return
        }

        batch.forEach { delegate.handle(it) }
    }
}

