package io.github.aeshen.observability.config.sink

data class OpenTelemetry(
    val endpoint: String = "http://localhost:4318/v1/logs",
    val serviceName: String = "observability",
    val serviceVersion: String? = null,
    val instrumentationScopeName: String = "io.github.aeshen.observability",
    val headers: Map<String, String> = emptyMap(),
    val scheduleDelayMillis: Long = 200,
    val exporterTimeoutMillis: Long = 30000,
    val maxQueueSize: Int = 2048,
    val maxExportBatchSize: Int = 512,
) : SinkConfig
