package io.github.aeshen.observability.enricher.builtin

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.enricher.MetadataEnricher

/**
 * Built-in metadata enricher that adds host, pod, and node information.
 *
 * Adds metadata fields for tracking which host, pod, or node instance processed the event.
 * Useful for debugging distribution-specific issues in multi-instance deployments.
 *
 * Thread-safe.
 *
 * @param hostname The hostname or pod name
 * @param nodeId Optional node/cluster identifier (e.g., Kubernetes node name, instance ID)
 */
class HostEnricher(
    private val hostname: String,
    private val nodeId: String? = null,
) : MetadataEnricher {
    override fun enrich(encoded: EncodedEvent) {
        encoded.metadata["hostname"] = hostname
        if (nodeId != null) {
            encoded.metadata["nodeId"] = nodeId
        }
    }
}
