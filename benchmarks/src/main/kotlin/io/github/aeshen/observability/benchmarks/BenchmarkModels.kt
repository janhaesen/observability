package io.github.aeshen.observability.benchmarks

import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics
import io.github.aeshen.observability.diagnostics.OperationalMetricsSnapshot
import io.github.aeshen.observability.sink.ObservabilitySink

internal const val DEFAULT_WARMUP_EVENTS = 1_000

internal data class BenchmarkScenario(
    val name: String,
    val category: String,
    val description: String,
    val eventCount: Int,
    val warmupEventCount: Int = minOf(eventCount, DEFAULT_WARMUP_EVENTS),
    val buildSink: (InMemoryOperationalDiagnostics) -> ObservabilitySink,
)

internal data class BenchmarkResult(
    val name: String,
    val eventCount: Int,
    val producerElapsedNanos: Long,
    val endToEndElapsedNanos: Long,
    val metrics: OperationalMetricsSnapshot,
)

internal data class ScenarioExecution(
    val sink: ObservabilitySink,
    val diagnostics: InMemoryOperationalDiagnostics,
    val producerFinishedAt: Long,
)
