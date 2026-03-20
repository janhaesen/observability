package io.github.aeshen.observability.transport

import io.github.aeshen.observability.codec.EncodedEvent

fun interface MetadataEnricher {
    fun enrich(encoded: EncodedEvent)
}
