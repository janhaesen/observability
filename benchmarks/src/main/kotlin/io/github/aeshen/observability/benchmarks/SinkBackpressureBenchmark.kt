package io.github.aeshen.observability.benchmarks

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.event
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.decorator.AsyncObservabilitySink
import kotlin.system.measureTimeMillis

private const val EVENT_COUNT = 100_000
private const val MILLIS_PER_SECOND = 1000.0

fun main() {
    val scenarios =
        listOf(
            "direct" to NoopSink(),
            "async-cap-128" to AsyncObservabilitySink(delegate = NoopSink(), capacity = 128, failOnDrop = true),
            "async-cap-2048" to AsyncObservabilitySink(delegate = NoopSink(), capacity = 2048, failOnDrop = true),
        )

    println("Running backpressure benchmark with $EVENT_COUNT events")
    for ((name, sink) in scenarios) {
        val elapsedMs =
            measureTimeMillis {
                sink.use {
                    repeat(EVENT_COUNT) { idx ->
                        it.handle(sampleEvent("event-$idx"))
                    }
                }
            }

        val throughput = EVENT_COUNT.toDouble() / elapsedMs.toDouble().coerceAtLeast(1.0) * MILLIS_PER_SECOND
        println("$name: elapsed=${elapsedMs}ms throughput=${"%.2f".format(throughput)} events/s")
    }
}

private class NoopSink : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        // intentional no-op sink for throughput measurement
    }
}

private fun sampleEvent(payload: String): EncodedEvent =
    EncodedEvent(
        original =
            event(BenchmarkEvent.BENCH) {
                level(EventLevel.INFO)
                context(ObservabilityContext.empty())
            },
        encoded = payload.toByteArray(Charsets.UTF_8),
    )

private enum class BenchmarkEvent(
    override val eventName: String? = null,
) : EventName {
    BENCH("sink.backpressure.bench"),
}
