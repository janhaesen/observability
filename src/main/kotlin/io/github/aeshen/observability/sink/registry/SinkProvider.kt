package io.github.aeshen.observability.sink.registry

import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink

/**
 * Provider that can materialize a sink from a SinkConfig implementation.
 */
fun interface SinkProvider {
    fun create(config: SinkConfig): ObservabilitySink?
}
