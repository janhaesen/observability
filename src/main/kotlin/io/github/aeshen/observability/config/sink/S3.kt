package io.github.aeshen.observability.config.sink

import java.net.URI

data class S3
    @JvmOverloads
    constructor(
        val bucket: String,
        val region: String,
        val keyPrefix: String = "observability/",
        val endpoint: String? = null,
        val timeoutMillis: Long = 30_000,
    ) : SinkConfig {
        init {
            require(bucket.isNotBlank()) { "bucket must not be blank." }
            require(region.isNotBlank()) { "region must not be blank." }
            require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank." }
            require(timeoutMillis > 0) { "timeoutMillis must be greater than 0." }
            endpoint?.let { ep ->
                val uri = URI.create(ep)
                require(uri.isAbsolute) { "endpoint must be an absolute URI." }
            }
        }
    }
