package io.github.aeshen.observability.spring

import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder

class ObservabilityMetricsBinder(
    private val diagnostics: ObservabilityDiagnostics,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        if (diagnostics !is InMemoryOperationalDiagnostics) return

        FunctionCounter.builder("observability.sink.errors.handle", diagnostics) {
            it.metricsSnapshot().sinkHandleErrors.toDouble()
        }.description("Total number of sink handle errors").register(registry)

        FunctionCounter.builder("observability.sink.errors.close", diagnostics) {
            it.metricsSnapshot().sinkCloseErrors.toDouble()
        }.description("Total number of sink close errors").register(registry)

        FunctionCounter.builder("observability.async.drops", diagnostics) {
            it.metricsSnapshot().asyncDrops.toDouble()
        }.description("Total number of events dropped by the async queue").register(registry)

        FunctionCounter.builder("observability.async.worker.errors", diagnostics) {
            it.metricsSnapshot().asyncWorkerErrors.toDouble()
        }.description("Total number of async worker errors").register(registry)

        FunctionCounter.builder("observability.retry.exhaustions", diagnostics) {
            it.metricsSnapshot().retryExhaustions.toDouble()
        }.description("Total number of retry exhaustions").register(registry)

        FunctionCounter.builder("observability.batch.flush.successes", diagnostics) {
            it.metricsSnapshot().batchFlushSuccesses.toDouble()
        }.description("Total number of successful batch flushes").register(registry)

        FunctionCounter.builder("observability.batch.flush.failures", diagnostics) {
            it.metricsSnapshot().batchFlushFailures.toDouble()
        }.description("Total number of failed batch flushes").register(registry)

        FunctionCounter.builder("observability.batch.flushed.events", diagnostics) {
            it.metricsSnapshot().batchFlushedEvents.toDouble()
        }.description("Total number of events flushed in batches").register(registry)

        registry.gauge("observability.async.queue.depth", diagnostics) {
            it.metricsSnapshot().asyncQueueDepth.toDouble()
        }

        registry.gauge("observability.async.queue.capacity", diagnostics) {
            it.metricsSnapshot().asyncQueueCapacity.toDouble()
        }
    }
}
