package io.github.aeshen.observability.enricher.builtin

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.enricher.MetadataEnricher

/**
 * Built-in metadata enricher that adds environment and region information.
 *
 * Adds metadata fields for environment (e.g., "prod", "staging", "dev") and region/zone.
 * Useful for debugging issues specific to certain deployments or geographic regions.
 *
 * Thread-safe.
 *
 * @param environment The deployment environment (e.g., "prod", "staging", "dev")
 * @param region The deployment region or availability zone
 */
class EnvironmentEnricher(
    private val environment: String,
    private val region: String,
) : MetadataEnricher {
    override fun enrich(encoded: EncodedEvent) {
        encoded.metadata["environment"] = environment
        encoded.metadata["region"] = region
    }
}
