package io.github.aeshen.observability.transport.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.transport.MetadataEnricher

class TimestampMetadataEnricher : MetadataEnricher {
    override fun enrich(encoded: EncodedEvent) {
        encoded.metadata["ingestedAt"] = System.currentTimeMillis()
    }
}
