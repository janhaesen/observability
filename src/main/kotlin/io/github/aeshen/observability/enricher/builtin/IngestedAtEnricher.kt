package io.github.aeshen.observability.enricher.builtin

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.enricher.MetadataEnricher

/**
 * Built-in metadata enricher that adds the ingestion timestamp (when the event was encoded).
 *
 * Adds a `ingestedAt` field to the event metadata with the current system time in milliseconds.
 * This enricher is applied after encoding but before encryption or sink delivery.
 *
 * Thread-safe.
 */
object IngestedAtEnricher : MetadataEnricher {
    override fun enrich(encoded: EncodedEvent) {
        encoded.metadata["ingestedAt"] = System.currentTimeMillis()
    }
}
