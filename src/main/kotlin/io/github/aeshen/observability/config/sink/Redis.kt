package io.github.aeshen.observability.config.sink

data class Redis
    @JvmOverloads
    constructor(
        val uri: String,
        val streamKey: String,
        val maxlen: Long? = null,
        val timeoutMillis: Long = 5_000,
    ) : SinkConfig {
        init {
            require(uri.isNotBlank()) { "uri must not be blank." }
            require(streamKey.isNotBlank()) { "streamKey must not be blank." }
            require(timeoutMillis > 0) { "timeoutMillis must be greater than 0." }
            maxlen?.let { require(it > 0) { "maxlen must be greater than 0 when specified." } }
        }
    }
