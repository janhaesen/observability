package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.S3
import io.github.aeshen.observability.sink.BatchCapableObservabilitySink
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.GZIPOutputStream

private val DATE_PATH_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC)

internal class S3ObservabilitySink internal constructor(
    private val uploader: S3Uploader,
    private val keyPrefix: String,
    private val closeAction: (() -> Unit)? = null,
) : BatchCapableObservabilitySink {
    override fun handle(event: EncodedEvent) {
        handleBatch(listOf(event))
    }

    override fun handleBatch(events: List<EncodedEvent>) {
        if (events.isEmpty()) return
        val key = buildKey()
        val bytes = compress(events)
        uploader.upload(key, bytes)
    }

    override fun close() {
        closeAction?.invoke()
    }

    private fun buildKey(): String {
        val datePath = DATE_PATH_FORMATTER.format(Instant.now())
        return "$keyPrefix$datePath/${UUID.randomUUID()}.jsonl.gz"
    }

    private fun compress(events: List<EncodedEvent>): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            events.forEach { event ->
                gz.write(event.encoded)
                if (event.encoded.lastOrNull() != '\n'.code.toByte()) {
                    gz.write('\n'.code)
                }
            }
        }
        return baos.toByteArray()
    }

    companion object {
        fun fromConfig(config: S3): S3ObservabilitySink {
            val clientBuilder =
                S3Client
                    .builder()
                    .region(Region.of(config.region))
                    .overrideConfiguration { c ->
                        c.apiCallTimeout(Duration.ofMillis(config.timeoutMillis))
                    }

            config.endpoint?.let { ep ->
                clientBuilder
                    .endpointOverride(URI.create(ep))
                    .serviceConfiguration { s3Config -> s3Config.pathStyleAccessEnabled(true) }
            }

            val client = clientBuilder.build()
            val uploader =
                S3Uploader { key, bytes ->
                    client.putObject(
                        PutObjectRequest
                            .builder()
                            .bucket(config.bucket)
                            .key(key)
                            .contentType("application/gzip")
                            .build(),
                        RequestBody.fromBytes(bytes),
                    )
                }

            return S3ObservabilitySink(uploader, config.keyPrefix) { client.close() }
        }
    }
}

internal fun interface S3Uploader {
    fun upload(
        key: String,
        bytes: ByteArray,
    )
}
