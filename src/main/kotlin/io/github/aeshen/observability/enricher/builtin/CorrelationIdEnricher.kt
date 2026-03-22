package io.github.aeshen.observability.enricher.builtin

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.enricher.MetadataEnricher

/**
 * Built-in metadata enricher that adds correlation identifiers.
 *
 * Adds metadata fields for request/trace correlation, useful for linking related events
 * across distributed systems. Typically sourced from ambient context (e.g., MDC, ThreadLocal).
 *
 * Thread-safe if the supplier lambdas are thread-safe.
 *
 * @param traceIdSupplier Function to provide the current trace ID, or null if not available
 * @param requestIdSupplier Function to provide the current request ID, or null if not available
 */
class CorrelationIdEnricher(
    private val traceIdSupplier: () -> String? = { null },
    private val requestIdSupplier: () -> String? = { null },
) : MetadataEnricher {
    override fun enrich(encoded: EncodedEvent) {
        traceIdSupplier()?.let { encoded.metadata["traceId"] = it }
        requestIdSupplier()?.let { encoded.metadata["requestId"] = it }
    }
}
