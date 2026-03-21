package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.sink.ObservabilitySink
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val POLL_TIMEOUT_MILLIS = 100L

/**
 * Decorator that offloads sink writes to a single background worker.
 */
class AsyncObservabilitySink(
    private val delegate: ObservabilitySink,
    private val capacity: Int = 1024,
    private val offerTimeoutMillis: Long = 50L,
    private val failOnDrop: Boolean = false,
    private val closeTimeoutMillis: Long = 5000L,
    private val shutdownPolicy: ShutdownPolicy = ShutdownPolicy.DRAIN,
    private val diagnostics: ObservabilityDiagnostics = ObservabilityDiagnostics.NOOP,
    private val onWorkerFailure: (Exception) -> Unit = {},
    private val joinWorker: (Thread, Long) -> Unit = { thread, timeout -> thread.join(timeout) },
) : ObservabilitySink {
    init {
        require(capacity > 0) { "capacity must be greater than 0." }
        require(offerTimeoutMillis >= 0) { "offerTimeoutMillis must be greater than or equal to 0." }
        require(closeTimeoutMillis > 0) { "closeTimeoutMillis must be greater than 0." }
    }

    enum class ShutdownPolicy {
        DRAIN,
        DROP_PENDING,
    }

    enum class DropReason {
        QUEUE_FULL,
        CLOSED,
        DROP_PENDING_ON_CLOSE,
    }

    private val queue = ArrayBlockingQueue<EncodedEvent>(capacity)
    private val accepting = AtomicBoolean(true)
    private val workerFailure = AtomicReference<Exception?>(null)
    private val worker =
        Thread({ runLoop() }, "observability-async-sink").apply {
            isDaemon = true
            start()
        }

    override fun handle(event: EncodedEvent) {
        val snapshot = event.copy(metadata = event.metadata.toMutableMap())
        if (!accepting.get()) {
            diagnostics.onAsyncDrop(snapshot, DropReason.CLOSED.name)
            check(false) { "AsyncObservabilitySink is closed." }
        }

        workerFailure.get()?.let { failure ->
            throw IllegalStateException("Async sink worker has failed.", failure)
        }

        val accepted = queue.offer(snapshot, offerTimeoutMillis, TimeUnit.MILLISECONDS)
        if (!accepted) {
            diagnostics.onAsyncDrop(snapshot, DropReason.QUEUE_FULL.name)
            if (failOnDrop) {
                error("Async sink queue is full (capacity=$capacity).")
            }
        }
    }

    override fun close() {
        accepting.set(false)
        if (shutdownPolicy == ShutdownPolicy.DROP_PENDING) {
            val dropped = mutableListOf<EncodedEvent>()
            queue.drainTo(dropped)
            dropped.forEach {
                diagnostics.onAsyncDrop(it, DropReason.DROP_PENDING_ON_CLOSE.name)
            }
            worker.interrupt()
        }

        joinWorker(worker, closeTimeoutMillis)

        var closeFailure: Exception? = null
        if (worker.isAlive) {
            worker.interrupt()
            closeFailure =
                IllegalStateException(
                    "Async sink worker did not terminate within ${closeTimeoutMillis}ms. " +
                        "Tune closeTimeoutMillis or use DROP_PENDING.",
                )
        }

        try {
            delegate.close()
        } catch (e: Exception) {
            if (closeFailure == null) {
                closeFailure = e
            } else {
                closeFailure.addSuppressed(e)
            }
        }

        workerFailure.get()?.let { failure ->
            if (closeFailure == null) {
                closeFailure = IllegalStateException("Async sink worker failed before close.", failure)
            } else {
                closeFailure.addSuppressed(failure)
            }
        }

        closeFailure?.let { throw it }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun runLoop() {
        while (accepting.get() || queue.isNotEmpty()) {
            val event =
                try {
                    queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    if (!accepting.get()) {
                        break
                    }
                    continue
                } ?: continue

            try {
                delegate.handle(event)
            } catch (t: Exception) {
                diagnostics.onAsyncWorkerError(t)
                onWorkerFailure(t)
                if (!accepting.get()) {
                    break
                }
                workerFailure.compareAndSet(null, t)
                accepting.set(false)
                break
            }
        }
    }
}
