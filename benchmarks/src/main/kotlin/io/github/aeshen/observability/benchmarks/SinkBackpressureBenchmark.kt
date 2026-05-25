package io.github.aeshen.observability.benchmarks

fun main() = main(emptyArray())

fun main(args: Array<String>) {
    val scenarios = benchmarkScenarios()
    val selected = filterScenarios(scenarios, args.toList())

    println("Running ${selected.size} benchmark scenario(s)")
    if (args.isNotEmpty()) {
        println("Scenario filter: ${args.joinToString(", ")}")
    }
    println()
    println(
        "Each scenario performs a warmup pass first. " +
            "Retry benchmarks inject zero-cost backoff to isolate decorator overhead.",
    )
    println()

    printScenarioSummary(selected)
    println()
    printTableHeader()

    selected.forEach { scenario ->
        warmupScenario(scenario)
        val result = runScenario(scenario)
        printScenarioRow(result)
    }

    println()
    println("Legend:")
    println("- producerMs / producerEvS: enqueue or direct handle cost before close/drain")
    println("- endToEndMs / endToEndEvS: full run including close and drain")
    println("- avg*Us values are coarse averages derived from total elapsed time, not per-event timers")
}
