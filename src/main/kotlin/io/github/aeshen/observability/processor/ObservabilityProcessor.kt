package io.github.aeshen.observability.processor

import io.github.aeshen.observability.codec.EncodedEvent

interface ObservabilityProcessor {
    fun process(event: EncodedEvent): EncodedEvent
}
