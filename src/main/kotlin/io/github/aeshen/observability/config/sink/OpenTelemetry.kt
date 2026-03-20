package io.github.aeshen.observability.config.sink

data class OpenTelemetry(
    val endpoint: String = "http://localhost:4318/v1/logs",
    val serviceName: String = "observability",
    val serviceVersion: String? = null,
    val instrumentationScopeName: String = "io.github.aeshen.observability",
    val headers: Map<String, String> = emptyMap(),
) : SinkConfig
