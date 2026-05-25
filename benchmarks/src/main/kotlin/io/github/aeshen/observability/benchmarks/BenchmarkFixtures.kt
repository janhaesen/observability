package io.github.aeshen.observability.benchmarks

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.event
import io.github.aeshen.observability.sink.BatchCapableObservabilitySink
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink

private const val BENCHMARK_EVENT_NAME = "sink.backpressure.bench"
private const val SEQUENCE_METADATA_KEY = "sequence"

internal fun sampleEvent(sequence: Int): EncodedEvent =
    EncodedEvent(
        original =
            event(BenchmarkEvent.BENCH) {
                level(EventLevel.INFO)
                context(ObservabilityContext.empty())
            },
        encoded = "event-$sequence".toByteArray(Charsets.UTF_8),
        metadata = mutableMapOf(SEQUENCE_METADATA_KEY to sequence),
    )

private fun spinDelay(delayNanos: Long) {
    if (delayNanos <= 0L) return

    val startedAt = System.nanoTime()
    while (System.nanoTime() - startedAt < delayNanos) {
        Thread.onSpinWait()
    }
}

internal class NoopSink : ObservabilitySink {
    override fun handle(event: EncodedEvent) = Unit
}

internal open class FixedDelaySink(
    private val delayNanos: Long,
) : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        spinDelay(delayNanos)
    }
}

internal class BatchCapableDelaySink(
    delayNanos: Long,
) : FixedDelaySink(delayNanos), BatchCapableObservabilitySink {
    private val batchDelayNanos = delayNanos

    override fun handleBatch(events: List<EncodedEvent>) {
        spinDelay(batchDelayNanos)
    }
}

internal class FailOnceEveryNthEventSink(
    private val failEvery: Int,
) : ObservabilitySink {
    private val failedSequences = mutableSetOf<Int>()

    override fun handle(event: EncodedEvent) {
        val sequence = event.metadata[SEQUENCE_METADATA_KEY] as? Int ?: return
        if (sequence == 0 || sequence % failEvery != 0) {
            return
        }

        if (failedSequences.add(sequence)) {
            error("Synthetic retry trigger for sequence=$sequence")
        }
    }
}

private enum class BenchmarkEvent(
    override val eventName: String? = null,
) : EventName {
    BENCH(BENCHMARK_EVENT_NAME),
}
