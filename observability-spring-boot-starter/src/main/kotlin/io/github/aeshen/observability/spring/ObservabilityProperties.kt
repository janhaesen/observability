package io.github.aeshen.observability.spring

import io.github.aeshen.observability.ObservabilityFactory.Profile
import io.github.aeshen.observability.config.encryption.AesGcm
import io.github.aeshen.observability.config.encryption.EncryptionConfig
import io.github.aeshen.observability.config.encryption.RsaKeyWrapped
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.config.sink.File
import io.github.aeshen.observability.config.sink.Http
import io.github.aeshen.observability.config.sink.HttpMethod
import io.github.aeshen.observability.config.sink.Kafka
import io.github.aeshen.observability.config.sink.OpenTelemetry
import io.github.aeshen.observability.config.sink.Redis
import io.github.aeshen.observability.config.sink.S3
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.config.sink.Slf4j
import io.github.aeshen.observability.config.sink.Webhook
import io.github.aeshen.observability.config.sink.ZipFile
import io.github.aeshen.observability.sink.decorator.AsyncObservabilitySink
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Paths
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties(prefix = "observability")
class ObservabilityProperties {
    var sinks: List<SinkProperties> = emptyList()
    var failOnSinkError: Boolean = false
    var profile: Profile = Profile.STANDARD
    var encryption: EncryptionProperties? = null
    var async: AsyncProperties = AsyncProperties()
}

class SinkProperties {
    var type: String? = null
    var endpoint: String? = null
    var method: String = "POST"
    var headers: Map<String, String> = emptyMap()
    var timeoutMillis: Long? = null
    var secret: String? = null
    var signatureHeader: String = "X-Hub-Signature-256"
    var bootstrapServers: String? = null
    var topic: String? = null
    var clientId: String = "observability-sink"
    var additionalProperties: Map<String, String> = emptyMap()
    var bucket: String? = null
    var region: String? = null
    var keyPrefix: String = "observability/"
    var uri: String? = null
    var streamKey: String? = null
    var maxlen: Long? = null
    var serviceName: String = "observability"
    var serviceVersion: String? = null
    var instrumentationScopeName: String = "io.github.aeshen.observability"
    var scheduleDelayMillis: Long? = null
    var exporterTimeoutMillis: Long? = null
    var maxQueueSize: Int? = null
    var maxExportBatchSize: Int? = null
    var logger: String? = null
    var path: String? = null

    @Suppress(
        "ComplexMethod",
        "detekt.ComplexMethod",
        "LongMethod",
        "detekt.LongMethod",
        "MagicNumber",
        "detekt.MagicNumber",
    )
    fun toSinkConfig(): SinkConfig {
        val t = type?.lowercase() ?: error("Sink type is required")
        return when (t) {
            "console" -> Console
            "slf4j" -> {
                val loggerClass = Class.forName(logger ?: "io.github.aeshen.observability.Observability").kotlin
                Slf4j(loggerClass)
            }
            "file" -> File(Paths.get(path ?: error("path is required for file sink")))
            "zipfile", "zip-file" -> ZipFile(Paths.get(path ?: error("path is required for zipfile sink")))
            "http" ->
                Http(
                    endpoint = endpoint ?: error("endpoint is required for http sink"),
                    method = HttpMethod.valueOf(method.uppercase()),
                    headers = headers,
                    timeoutMillis = timeoutMillis ?: 5000L,
                )
            "webhook" ->
                Webhook(
                    endpoint = endpoint ?: error("endpoint is required for webhook sink"),
                    secret = secret,
                    signatureHeader = signatureHeader,
                    headers = headers,
                    timeoutMillis = timeoutMillis ?: 5000L,
                )
            "kafka" ->
                Kafka(
                    bootstrapServers = bootstrapServers ?: error("bootstrapServers is required for kafka sink"),
                    topic = topic ?: error("topic is required for kafka sink"),
                    clientId = clientId,
                    additionalProperties = additionalProperties,
                    timeoutMillis = timeoutMillis ?: 5000L,
                )
            "s3" ->
                S3(
                    bucket = bucket ?: error("bucket is required for s3 sink"),
                    region = region ?: error("region is required for s3 sink"),
                    keyPrefix = keyPrefix,
                    endpoint = endpoint,
                    timeoutMillis = timeoutMillis ?: 30000L,
                )
            "redis" ->
                Redis(
                    uri = uri ?: error("uri is required for redis sink"),
                    streamKey = streamKey ?: error("streamKey is required for redis sink"),
                    maxlen = maxlen,
                    timeoutMillis = timeoutMillis ?: 5000L,
                )
            "opentelemetry", "open-telemetry", "otel" ->
                OpenTelemetry(
                    endpoint = endpoint ?: "http://localhost:4318/v1/logs",
                    serviceName = serviceName,
                    serviceVersion = serviceVersion,
                    instrumentationScopeName = instrumentationScopeName,
                    headers = headers,
                    scheduleDelayMillis = scheduleDelayMillis ?: 200L,
                    exporterTimeoutMillis = exporterTimeoutMillis ?: 30000L,
                    maxQueueSize = maxQueueSize ?: 2048,
                    maxExportBatchSize = maxExportBatchSize ?: 512,
                )
            else -> error("Unknown sink type: $t")
        }
    }
}

@Suppress("MagicNumber", "detekt.MagicNumber")
class EncryptionProperties {
    var type: String? = null
    var aesKeyHex: String? = null
    var aesKeyBase64: String? = null
    var recipientPublicKeyPem: String? = null

    fun toEncryptionConfig(): EncryptionConfig {
        val t = type?.lowercase() ?: error("Encryption type is required")
        return when (t) {
            "aes-gcm", "aes_gcm" -> {
                val rawKey =
                    when {
                        aesKeyHex != null -> hexToBytes(aesKeyHex!!)
                        aesKeyBase64 != null -> Base64.getDecoder().decode(aesKeyBase64)
                        else -> error("aesKeyHex or aesKeyBase64 is required for aes-gcm encryption")
                    }
                AesGcm(SecretKeySpec(rawKey, "AES"))
            }
            "rsa-key-wrapped", "rsa_key_wrapped" -> {
                RsaKeyWrapped(
                    recipientPublicKeyPem ?: error("recipientPublicKeyPem is required for rsa-key-wrapped encryption"),
                )
            }
            else -> error("Unknown encryption type: $t")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val h1 = Character.digit(hex[i], 16)
            val h2 = Character.digit(hex[i + 1], 16)
            data[i / 2] = ((h1 shl 4) or h2).toByte()
            i += 2
        }
        return data
    }
}

@Suppress("MagicNumber", "detekt.MagicNumber")
class AsyncProperties {
    var enabled: Boolean = false
    var capacity: Int = 1024
    var offerTimeoutMillis: Long = 50L
    var failOnDrop: Boolean = false
    var closeTimeoutMillis: Long = 5000L
    var shutdownPolicy: AsyncObservabilitySink.ShutdownPolicy = AsyncObservabilitySink.ShutdownPolicy.DRAIN
}
