package io.github.aeshen.observability.benchmarks

import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics

internal fun warmupScenario(scenario: BenchmarkScenario) {
    executeScenario(
        scenario = scenario,
        eventCount = scenario.warmupEventCount,
    ).sink.close()
}

internal fun runScenario(scenario: BenchmarkScenario): BenchmarkResult {
    val startedAt = System.nanoTime()
    val execution =
        executeScenario(
            scenario = scenario,
            eventCount = scenario.eventCount,
        )
    val producerElapsedNanos = execution.producerFinishedAt - startedAt

    execution.sink.close()
    val completedAt = System.nanoTime()

    return BenchmarkResult(
        name = scenario.name,
        eventCount = scenario.eventCount,
        producerElapsedNanos = producerElapsedNanos,
        endToEndElapsedNanos = completedAt - startedAt,
        metrics = execution.diagnostics.metricsSnapshot(),
    )
}

private fun executeScenario(
    scenario: BenchmarkScenario,
    eventCount: Int,
): ScenarioExecution {
    val diagnostics = InMemoryOperationalDiagnostics()
    val sink = scenario.buildSink(diagnostics)

    repeat(eventCount) { sequence ->
        sink.handle(sampleEvent(sequence))
    }

    return ScenarioExecution(
        sink = sink,
        diagnostics = diagnostics,
        producerFinishedAt = System.nanoTime(),
    )
}
