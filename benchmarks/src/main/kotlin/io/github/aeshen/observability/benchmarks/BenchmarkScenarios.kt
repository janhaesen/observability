package io.github.aeshen.observability.benchmarks

import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics
import io.github.aeshen.observability.sink.decorator.AsyncObservabilitySink
import io.github.aeshen.observability.sink.decorator.BatchingObservabilitySink
import io.github.aeshen.observability.sink.decorator.RetryingObservabilitySink

private const val SMALL_DELAY_NANOS = 250_000L
private const val BACKPRESSURE_DELAY_NANOS = 500_000L
private const val FAIL_EVERY_FIFTH_EVENT = 5
private const val DIRECT_EVENT_COUNT = 100_000
private const val REPRESENTATIVE_EVENT_COUNT = 5_000
private const val RETRY_EVENT_COUNT = 25_000
private const val SMALL_ASYNC_CAPACITY = 128
private const val LARGE_ASYNC_CAPACITY = 2048
private const val STRESS_ASYNC_CAPACITY = 32
private const val BATCH_SIZE = 50
private const val BATCH_FLUSH_INTERVAL_MILLIS = 25L

internal fun benchmarkScenarios(): List<BenchmarkScenario> =
    buildList {
        add(directNoopScenario())
        add(asyncNoopScenario("async-noop-cap-128", SMALL_ASYNC_CAPACITY))
        add(asyncNoopScenario("async-noop-cap-2048", LARGE_ASYNC_CAPACITY))
        add(directDelayScenario())
        add(asyncBackpressureScenario())
        add(batchingDirectDelayScenario())
        add(batchingBatchCapableScenario())
        add(retryScenario())
    }

internal fun filterScenarios(
    scenarios: List<BenchmarkScenario>,
    rawArgs: List<String>,
): List<BenchmarkScenario> {
    val filters =
        rawArgs
            .flatMap { arg -> arg.split(',') }
            .map { token -> token.trim() }
            .filter { token -> token.isNotEmpty() }

    if (filters.isEmpty()) {
        return scenarios
    }

    val byName = scenarios.associateBy(BenchmarkScenario::name)
    val missing = filters.filterNot(byName::containsKey)
    require(missing.isEmpty()) {
        buildString {
            append("Unknown benchmark scenario(s): ")
            append(missing.joinToString(", "))
            append(". Available scenarios: ")
            append(scenarios.joinToString(", ", transform = BenchmarkScenario::name))
        }
    }

    return filters.mapNotNull(byName::get)
}

private fun directNoopScenario() =
    BenchmarkScenario(
        name = "direct-noop",
        category = "direct",
        description = "Baseline direct throughput against a noop sink.",
        eventCount = DIRECT_EVENT_COUNT,
        buildSink = { NoopSink() },
    )

private fun asyncNoopScenario(
    name: String,
    capacity: Int,
) = BenchmarkScenario(
    name = name,
    category = "async",
    description = "Async decorator over a noop sink with capacity=$capacity.",
    eventCount = DIRECT_EVENT_COUNT,
    buildSink = { diagnostics: InMemoryOperationalDiagnostics ->
        AsyncObservabilitySink(
            delegate = NoopSink(),
            capacity = capacity,
            failOnDrop = true,
            diagnostics = diagnostics,
        )
    },
)

private fun directDelayScenario() =
    BenchmarkScenario(
        name = "direct-delay-250us",
        category = "sink-behavior",
        description = "Direct delivery to a deterministic in-process delayed sink.",
        eventCount = REPRESENTATIVE_EVENT_COUNT,
        buildSink = { FixedDelaySink(SMALL_DELAY_NANOS) },
    )

private fun asyncBackpressureScenario() =
    BenchmarkScenario(
        name = "async-backpressure-queue-32",
        category = "backpressure",
        description = "Async queue stress over a slower sink to surface drops and queue saturation.",
        eventCount = REPRESENTATIVE_EVENT_COUNT,
        buildSink = { diagnostics: InMemoryOperationalDiagnostics ->
            AsyncObservabilitySink(
                delegate = FixedDelaySink(BACKPRESSURE_DELAY_NANOS),
                capacity = STRESS_ASYNC_CAPACITY,
                offerTimeoutMillis = 0,
                failOnDrop = false,
                diagnostics = diagnostics,
            )
        },
    )

private fun batchingDirectDelayScenario() =
    BenchmarkScenario(
        name = "batching-delay-direct",
        category = "batching",
        description = "Batching over a delegate that still pays delay per event.",
        eventCount = REPRESENTATIVE_EVENT_COUNT,
        buildSink = { diagnostics: InMemoryOperationalDiagnostics ->
            BatchingObservabilitySink(
                delegate = FixedDelaySink(SMALL_DELAY_NANOS),
                maxBatchSize = BATCH_SIZE,
                flushIntervalMillis = BATCH_FLUSH_INTERVAL_MILLIS,
                diagnostics = diagnostics,
            )
        },
    )

private fun batchingBatchCapableScenario() =
    BenchmarkScenario(
        name = "batching-delay-batch-capable",
        category = "batching",
        description = "Batching over a batch-capable delegate that pays delay once per batch.",
        eventCount = REPRESENTATIVE_EVENT_COUNT,
        buildSink = { diagnostics: InMemoryOperationalDiagnostics ->
            BatchingObservabilitySink(
                delegate = BatchCapableDelaySink(SMALL_DELAY_NANOS),
                maxBatchSize = BATCH_SIZE,
                flushIntervalMillis = BATCH_FLUSH_INTERVAL_MILLIS,
                diagnostics = diagnostics,
            )
        },
    )

private fun retryScenario() =
    BenchmarkScenario(
        name = "retry-once-every-5th",
        category = "retry",
        description = "Retry decorator where every fifth event fails once, then succeeds on retry.",
        eventCount = RETRY_EVENT_COUNT,
        buildSink = { diagnostics: InMemoryOperationalDiagnostics ->
            RetryingObservabilitySink(
                delegate = FailOnceEveryNthEventSink(FAIL_EVERY_FIFTH_EVENT),
                maxAttempts = 3,
                sleep = {},
                diagnostics = diagnostics,
            )
        },
    )
