package io.github.aeshen.observability.benchmarks

import java.util.Locale

private const val MILLIS_PER_SECOND = 1000.0
private const val NANOS_PER_MILLI = 1_000_000.0
private const val NANOS_PER_MICRO = 1_000.0
private const val TABLE_RULE_LENGTH = 173

private const val SCENARIO_WIDTH = 28
private const val EVENTS_WIDTH = 7
private const val PRODUCER_MS_WIDTH = 12
private const val END_TO_END_MS_WIDTH = 12
private const val PRODUCER_EVS_WIDTH = 14
private const val END_TO_END_EVS_WIDTH = 14
private const val AVG_PRODUCER_US_WIDTH = 15
private const val AVG_END_US_WIDTH = 12
private const val DROPS_WIDTH = 8
private const val QUEUE_MAX_WIDTH = 9
private const val BATCH_AVG_MS_WIDTH = 12
private const val RETRY_EXHAUSTIONS_WIDTH = 10
private const val DEFAULT_DECIMALS = 2

internal fun printScenarioSummary(scenarios: List<BenchmarkScenario>) {
    println("Scenarios:")
    scenarios.forEach { scenario ->
        println("- ${scenario.name} [${scenario.category}] — ${scenario.description}")
    }
}

internal fun printTableHeader() {
    println(
        listOf(
            "scenario".padEnd(SCENARIO_WIDTH),
            "events".padStart(EVENTS_WIDTH),
            "producerMs".padStart(PRODUCER_MS_WIDTH),
            "endToEndMs".padStart(END_TO_END_MS_WIDTH),
            "producerEvS".padStart(PRODUCER_EVS_WIDTH),
            "endToEndEvS".padStart(END_TO_END_EVS_WIDTH),
            "avgProducerUs".padStart(AVG_PRODUCER_US_WIDTH),
            "avgEndUs".padStart(AVG_END_US_WIDTH),
            "drops".padStart(DROPS_WIDTH),
            "queueMax".padStart(QUEUE_MAX_WIDTH),
            "batchAvgMs".padStart(BATCH_AVG_MS_WIDTH),
            "retryExh".padStart(RETRY_EXHAUSTIONS_WIDTH),
        ).joinToString(" | "),
    )
    println("-".repeat(TABLE_RULE_LENGTH))
}

internal fun printScenarioRow(result: BenchmarkResult) {
    val producerMillis = result.producerElapsedNanos / NANOS_PER_MILLI
    val endToEndMillis = result.endToEndElapsedNanos / NANOS_PER_MILLI
    val producerThroughput = throughput(result.eventCount, producerMillis)
    val endToEndThroughput = throughput(result.eventCount, endToEndMillis)
    val averageProducerMicros = averageMicros(result.producerElapsedNanos, result.eventCount)
    val averageEndToEndMicros = averageMicros(result.endToEndElapsedNanos, result.eventCount)

    println(
        listOf(
            result.name.padEnd(SCENARIO_WIDTH),
            result.eventCount.toString().padStart(EVENTS_WIDTH),
            formatDecimal(producerMillis).padStart(PRODUCER_MS_WIDTH),
            formatDecimal(endToEndMillis).padStart(END_TO_END_MS_WIDTH),
            formatDecimal(producerThroughput).padStart(PRODUCER_EVS_WIDTH),
            formatDecimal(endToEndThroughput).padStart(END_TO_END_EVS_WIDTH),
            formatDecimal(averageProducerMicros).padStart(AVG_PRODUCER_US_WIDTH),
            formatDecimal(averageEndToEndMicros).padStart(AVG_END_US_WIDTH),
            result.metrics.asyncDrops.toString().padStart(DROPS_WIDTH),
            result.metrics.asyncQueueDepthMax.toString().padStart(QUEUE_MAX_WIDTH),
            formatDecimal(result.metrics.averageBatchFlushMillis).padStart(BATCH_AVG_MS_WIDTH),
            result.metrics.retryExhaustions.toString().padStart(RETRY_EXHAUSTIONS_WIDTH),
        ).joinToString(" | "),
    )
}

private fun throughput(
    eventCount: Int,
    elapsedMillis: Double,
): Double = eventCount.toDouble() / elapsedMillis.coerceAtLeast(1.0) * MILLIS_PER_SECOND

private fun averageMicros(
    elapsedNanos: Long,
    eventCount: Int,
): Double = elapsedNanos.toDouble() / eventCount.toDouble().coerceAtLeast(1.0) / NANOS_PER_MICRO

private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.${DEFAULT_DECIMALS}f", value)
