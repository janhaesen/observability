package io.github.aeshen.observability.enricher.builtin

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.enricher.MetadataEnricher

/**
 * Built-in metadata enricher that adds version and build information.
 *
 * Adds metadata fields for tracking application version and build SHA.
 * Useful for correlating events with specific deployments.
 *
 * Thread-safe.
 *
 * @param version Application version (e.g., "1.0.0")
 * @param buildSha Build/commit SHA (e.g., git commit hash)
 */
class VersionEnricher(
    private val version: String,
    private val buildSha: String,
) : MetadataEnricher {
    override fun enrich(encoded: EncodedEvent) {
        encoded.metadata["version"] = version
        encoded.metadata["buildSha"] = buildSha
    }
}
