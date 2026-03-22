package io.github.aeshen.observability.enricher

import io.github.aeshen.observability.codec.EncodedEvent

fun interface MetadataEnricher {
    fun enrich(encoded: EncodedEvent)
}
