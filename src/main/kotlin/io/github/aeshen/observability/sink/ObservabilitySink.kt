package io.github.aeshen.observability.sink

import io.github.aeshen.observability.codec.EncodedEvent
import java.io.Closeable

/**
 * Sink receives already-encoded bytes and decides how/where to persist them.
 */
interface ObservabilitySink : Closeable {
    fun handle(event: EncodedEvent)

    override fun close() {}
}
