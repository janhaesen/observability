package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink

internal class ConsoleObservabilitySink : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        val payload = event.encoded.toString(Charsets.UTF_8)
        if (payload.endsWith('\n')) {
            print(payload)
        } else {
            println(payload)
        }
    }
}
