package io.github.aeshen.observability.codec

import io.github.aeshen.observability.ObservabilityEvent

/**
 * Encodes a log record to bytes. Kept separate so Logger can stay agnostic.
 */
fun interface ObservabilityCodec {
    fun encode(event: ObservabilityEvent): EncodedEvent
}
