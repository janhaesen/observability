package io.github.aeshen.observability.sink

import io.github.aeshen.observability.codec.EncodedEvent

/**
 * Optional sink contract that allows optimized batch handling.
 */
interface BatchCapableObservabilitySink : ObservabilitySink {
    fun handleBatch(events: List<EncodedEvent>) {
        events.forEach { handle(it) }
    }
}
