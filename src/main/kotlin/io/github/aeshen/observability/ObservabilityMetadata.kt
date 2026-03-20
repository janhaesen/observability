package io.github.aeshen.observability

import io.github.aeshen.observability.ObservabilityMetadata.Companion.from

/**
 * Lightweight metadata passed to sinks. Create via [from] for convenience.
 */
data class ObservabilityMetadata(
    val size: Int,
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(
            bytes: ByteArray,
            extras: Map<String, String> = emptyMap(),
        ): ObservabilityMetadata = ObservabilityMetadata(size = bytes.size, extras = extras)
    }
}
