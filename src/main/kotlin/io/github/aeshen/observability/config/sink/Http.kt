package io.github.aeshen.observability.config.sink

import java.net.URI

enum class HttpMethod {
    POST,
    PUT,
    PATCH,
}

data class Http
    @JvmOverloads
    constructor(
        val endpoint: String,
        val method: HttpMethod = HttpMethod.POST,
        val headers: Map<String, String> = emptyMap(),
        val timeoutMillis: Long = 5_000,
    ) : SinkConfig {
        init {
            require(endpoint.isNotBlank()) { "endpoint must not be blank." }
            require(timeoutMillis > 0) { "timeoutMillis must be greater than 0." }
            require(headers.keys.none { it.isBlank() }) { "headers must not contain blank keys." }

            val uri = URI.create(endpoint)
            require(uri.isAbsolute) { "endpoint must be an absolute URI." }
            require(uri.scheme == "http" || uri.scheme == "https") {
                "endpoint must use http or https scheme."
            }
        }
    }
