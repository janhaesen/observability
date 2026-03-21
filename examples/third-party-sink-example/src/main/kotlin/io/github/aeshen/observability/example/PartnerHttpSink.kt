package io.github.aeshen.observability.example

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink

class PartnerHttpSink(
    private val endpoint: String,
) : ObservabilitySink {
    private val received = mutableListOf<EncodedEvent>()

    override fun handle(event: EncodedEvent) {
        // Example sink keeps an in-memory copy; replace with real HTTP transport.
        received += event.copy(metadata = event.metadata.toMutableMap())
    }

    fun endpoint(): String = endpoint

    fun receivedEvents(): List<EncodedEvent> = received.toList()
}

