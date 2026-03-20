package io.github.aeshen.observability.transport

sealed interface TransportEnvelope {
    data class Json(
        val payload: ByteArray,
        val metadata: Map<String, Any?>,
    ) : TransportEnvelope {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Json

            if (!payload.contentEquals(other.payload)) return false
            if (metadata != other.metadata) return false

            return true
        }

        override fun hashCode(): Int {
            var result = payload.contentHashCode()
            result = 31 * result + metadata.hashCode()
            return result
        }
    }

    data class Binary(
        val payload: ByteArray,
        val metadata: Map<String, Any?>,
    ) : TransportEnvelope {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Binary

            if (!payload.contentEquals(other.payload)) return false
            if (metadata != other.metadata) return false

            return true
        }

        override fun hashCode(): Int {
            var result = payload.contentHashCode()
            result = 31 * result + metadata.hashCode()
            return result
        }
    }
}
