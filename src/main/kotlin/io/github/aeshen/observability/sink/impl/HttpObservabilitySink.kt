package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Http
import io.github.aeshen.observability.config.sink.HttpMethod
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299

internal class HttpObservabilitySink internal constructor(
    private val endpoint: URI,
    private val method: HttpMethod,
    private val headers: Map<String, String>,
    private val timeoutMillis: Long,
    private val client: HttpClient,
) : ObservabilitySink {
    constructor(config: Http) : this(
        endpoint = URI.create(config.endpoint),
        method = config.method,
        headers = config.headers,
        timeoutMillis = config.timeoutMillis,
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMillis)).build(),
    )

    override fun handle(event: EncodedEvent) {
        val body = HttpRequest.BodyPublishers.ofByteArray(event.encoded)
        val requestBuilder =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMillis))

        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        val request =
            when (method) {
                HttpMethod.POST -> requestBuilder.POST(body)
                HttpMethod.PUT -> requestBuilder.PUT(body)
                HttpMethod.PATCH -> requestBuilder.method("PATCH", body)
            }.build()

        val response = send(request)
        check(response.statusCode() in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            "HTTP sink request failed with status=${response.statusCode()} for endpoint=$endpoint."
        }
    }

    private fun send(request: HttpRequest): HttpResponse<Void> =
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (e: IOException) {
            throw IllegalStateException("HTTP sink request failed for endpoint=$endpoint.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("HTTP sink request interrupted for endpoint=$endpoint.", e)
        }
}
