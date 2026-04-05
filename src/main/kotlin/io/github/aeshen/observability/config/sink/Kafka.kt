package io.github.aeshen.observability.config.sink

data class Kafka
    @JvmOverloads
    constructor(
        val bootstrapServers: String,
        val topic: String,
        val clientId: String = "observability-sink",
        val additionalProperties: Map<String, String> = emptyMap(),
        val timeoutMillis: Long = 5_000,
    ) : SinkConfig {
        init {
            require(bootstrapServers.isNotBlank()) { "bootstrapServers must not be blank." }
            require(topic.isNotBlank()) { "topic must not be blank." }
            require(clientId.isNotBlank()) { "clientId must not be blank." }
            require(timeoutMillis > 0) { "timeoutMillis must be greater than 0." }
        }
    }
