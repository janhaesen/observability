package io.github.aeshen.observability.example

import io.github.aeshen.observability.config.sink.SinkConfig

data class PartnerSinkConfig(
    val endpoint: String,
) : SinkConfig
