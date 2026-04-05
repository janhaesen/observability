package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Webhook
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val HMAC_SHA256 = "HmacSHA256"

internal class WebhookObservabilitySink internal constructor(
    private val endpoint: URI,
    private val signingKey: SecretKeySpec,
    private val signatureHeader: String,
    private val headers: Map<String, String>,
    private val timeoutMillis: Long,
    private val client: HttpClient,
) : ObservabilitySink {
    constructor(config: Webhook) : this(
        endpoint = URI.create(config.endpoint),
        signingKey = SecretKeySpec(config.secret.toByteArray(Charsets.UTF_8), HMAC_SHA256),
        signatureHeader = config.signatureHeader,
        headers = config.headers,
        timeoutMillis = config.timeoutMillis,
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMillis)).build(),
    )

    override fun handle(event: EncodedEvent) {
        val signature = sign(event.encoded)
        val allHeaders = headers + mapOf(signatureHeader to signature)

        val requestBuilder =
            HttpRequest
                .newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofByteArray(event.encoded))
                .timeout(Duration.ofMillis(timeoutMillis))

        allHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }

        val response = send(requestBuilder.build())
        check(response.statusCode() in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            "Webhook sink request failed with status=${response.statusCode()} for endpoint=$endpoint."
        }
    }

    private fun sign(bytes: ByteArray): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(signingKey)
        return "sha256=" + mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun send(request: HttpRequest): HttpResponse<Void> =
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (e: IOException) {
            throw IllegalStateException("Webhook sink request failed for endpoint=$endpoint.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Webhook sink request interrupted for endpoint=$endpoint.", e)
        }
}
