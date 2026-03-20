package io.github.aeshen.observability.codec

import io.github.aeshen.observability.ObservabilityEvent

data class EncodedEvent(
    val original: ObservabilityEvent,
    val encoded: ByteArray, // JSON from codec
    val metadata: MutableMap<String, Any?> = mutableMapOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncodedEvent

        if (!encoded.contentEquals(other.encoded)) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = encoded.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}
