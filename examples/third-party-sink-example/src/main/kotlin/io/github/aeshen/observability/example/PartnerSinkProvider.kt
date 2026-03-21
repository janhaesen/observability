package io.github.aeshen.observability.example

import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkProvider

object PartnerSinkProvider : SinkProvider {
    override fun create(config: SinkConfig): ObservabilitySink? =
        if (config is PartnerSinkConfig) {
            PartnerHttpSink(config.endpoint)
        } else {
            null
        }
}
