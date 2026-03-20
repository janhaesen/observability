package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val POLL_TIMEOUT_MILLIS = 100L

/**
 * Decorator that offloads sink writes to a single background worker.
 */
class AsyncObservabilitySink(
    private val delegate: ObservabilitySink,
    private val capacity: Int = 1024,
    private val offerTimeoutMillis: Long = 50L,
    private val failOnDrop: Boolean = false,
    private val onError: (Throwable) -> Unit = { t -> System.err.println("Async sink worker error: ${t.message}") },
) : ObservabilitySink {
    private val queue = ArrayBlockingQueue<EncodedEvent>(capacity)
    private val accepting = AtomicBoolean(true)
    private val worker =
        Thread({ runLoop() }, "observability-async-sink").apply {
            isDaemon = true
            start()
        }

    override fun handle(event: EncodedEvent) {
        check(accepting.get()) { "AsyncObservabilitySink is closed." }
        val accepted = queue.offer(event.copy(metadata = event.metadata.toMutableMap()), offerTimeoutMillis, TimeUnit.MILLISECONDS)
        if (!accepted && failOnDrop) {
            error("Async sink queue is full (capacity=$capacity).")
        }
    }

    override fun close() {
        accepting.set(false)
        worker.join()
        delegate.close()
    }

    private fun runLoop() {
        while (accepting.get() || queue.isNotEmpty()) {
            val event = queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            try {
                delegate.handle(event)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }
}

