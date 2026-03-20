package io.github.aeshen.observability

import io.github.aeshen.observability.codec.impl.JsonLineCodec
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.processor.encryption.EncryptingObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.impl.ConsoleObservabilitySink
import io.github.aeshen.observability.sink.impl.FileObservabilitySink
import io.github.aeshen.observability.sink.impl.Slf4JObservabilitySink
import io.github.aeshen.observability.sink.impl.ZipFileObservabilitySink
import io.github.aeshen.observability.util.CryptoUtils
import java.nio.file.Path
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.reflect.KClass

object ObservabilityFactory {
    private const val BYTE_16 = 16
    private const val BYTE_24 = 24
    private const val BYTE_32 = 32

    sealed interface EncryptionConfig {
        data class AesGcm(
            val aesKey: SecretKey,
        ) : EncryptionConfig

        data class RsaKeyWrapped(
            val recipientPublicKeyPem: String,
        ) : EncryptionConfig
    }

    data class Slf4jConfig(
        val logger: KClass<*>,
    )

    sealed interface SinkConfig {
        data object Console : SinkConfig

        data class Slf4j(
            val logger: KClass<*>,
        ) : SinkConfig

        data class File(
            val path: Path,
        ) : SinkConfig

        data class ZipFile(
            val path: Path,
        ) : SinkConfig

        // data class OpenTelemetry(
        //     val openTelemetry: OpenTelemetry,
        //     val serviceName: String = "app"
        // ) : SinkConfig
    }

    data class Config(
        val sinks: List<SinkConfig>,
        val encryption: EncryptionConfig? = null,
        val failOnSinkError: Boolean = false,
    ) {
        companion object {
            fun aesGcmFromRawKeyBytes(rawKey: ByteArray) =
                EncryptionConfig.AesGcm(SecretKeySpec(rawKey, "AES")).also {
                    require(
                        rawKey.size == BYTE_16 ||
                            rawKey.size == BYTE_24 ||
                            rawKey.size == BYTE_32,
                    ) {
                        "AES key must be $BYTE_16, $BYTE_24, or $BYTE_32 bytes."
                    }
                }
        }
    }

    fun create(config: Config): Observability {
        val sinks: List<ObservabilitySink> = buildSinks(config)

        require(sinks.isNotEmpty()) { "At least one sink must be configured." }

        // Build processors (encryption, etc)
        val processors: List<ObservabilityProcessor> = buildProcessors(config)

        return ObservabilityPipeline(
            codec = JsonLineCodec(),
            processors = processors,
            sinks = sinks,
            failOnSinkError = config.failOnSinkError,
        )
    }

    private fun buildSinks(config: Config): List<ObservabilitySink> =
        config.sinks.map { sink ->
            when (sink) {
                is SinkConfig.Console -> ConsoleObservabilitySink()
                is SinkConfig.Slf4j -> Slf4JObservabilitySink(sink.logger)
                is SinkConfig.File -> FileObservabilitySink(sink.path)
                is SinkConfig.ZipFile -> ZipFileObservabilitySink(sink.path)
                // is SinkConfig.OpenTelemetry ->
                //     OpenTelemetrySink(sink.openTelemetry, sink.serviceName)
            }
        }

    private fun buildProcessors(config: Config): List<ObservabilityProcessor> =
        when (val enc = config.encryption) {
            null -> {
                emptyList()
            }

            is EncryptionConfig.AesGcm -> {
                listOf(
                    EncryptingObservabilityProcessor.aesGcm(
                        key = enc.aesKey,
                        secureRandomBytes = { n -> CryptoUtils.randomBytes(n) },
                    ),
                )
            }

            is EncryptionConfig.RsaKeyWrapped -> {
                val pub = CryptoUtils.loadPublicKeyFromPem(enc.recipientPublicKeyPem)
                listOf(
                    EncryptingObservabilityProcessor.aesGcmWithRsaWrappedDataKey(
                        recipientPublicKey = pub,
                        secureRandomBytes = { n -> CryptoUtils.randomBytes(n) },
                    ),
                )
            }
        }
}
