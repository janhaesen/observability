package io.github.aeshen.observability

import io.github.aeshen.observability.codec.impl.JsonLineCodec
import io.github.aeshen.observability.config.encryption.AesGcm
import io.github.aeshen.observability.config.encryption.EncryptionConfig
import io.github.aeshen.observability.config.encryption.RsaKeyWrapped
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.processor.encryption.EncryptingObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry
import io.github.aeshen.observability.util.CryptoUtils
import javax.crypto.spec.SecretKeySpec

object ObservabilityFactory {
    private const val BYTE_16 = 16
    private const val BYTE_24 = 24
    private const val BYTE_32 = 32

    data class Config(
        val sinks: List<SinkConfig>,
        val encryption: EncryptionConfig? = null,
        val failOnSinkError: Boolean = false,
        val sinkRegistry: SinkRegistry = SinkRegistry.default(),
    ) {
        companion object {
            fun aesGcmFromRawKeyBytes(rawKey: ByteArray) =
                AesGcm(SecretKeySpec(rawKey, "AES")).also {
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

        val processors: List<ObservabilityProcessor> = buildProcessors(config.encryption)

        return pipeline(
            sinks = sinks,
            processors = processors,
            failOnSinkError = config.failOnSinkError,
        )
    }

    fun create(
        sinks: List<ObservabilitySink>,
        encryption: EncryptionConfig? = null,
        failOnSinkError: Boolean = false,
    ): Observability {
        require(sinks.isNotEmpty()) { "At least one sink must be configured." }

        val processors: List<ObservabilityProcessor> = buildProcessors(encryption)

        return pipeline(
            sinks = sinks,
            processors = processors,
            failOnSinkError = failOnSinkError,
        )
    }

    fun create(
        vararg sinks: ObservabilitySink,
        encryption: EncryptionConfig? = null,
        failOnSinkError: Boolean = false,
    ): Observability =
        create(
            sinks = sinks.toList(),
            encryption = encryption,
            failOnSinkError = failOnSinkError,
        )

    private fun pipeline(
        sinks: List<ObservabilitySink>,
        processors: List<ObservabilityProcessor>,
        failOnSinkError: Boolean,
    ): Observability =
        ObservabilityPipeline(
            codec = JsonLineCodec(),
            processors = processors,
            sinks = sinks,
            failOnSinkError = failOnSinkError,
        )

    private fun buildSinks(config: Config): List<ObservabilitySink> = config.sinkRegistry.resolveAll(config.sinks)

    private fun buildProcessors(encryption: EncryptionConfig?): List<ObservabilityProcessor> =
        when (val enc = encryption) {
            null -> {
                emptyList()
            }

            is AesGcm -> {
                listOf(
                    EncryptingObservabilityProcessor.aesGcm(
                        key = enc.aesKey,
                        secureRandomBytes = { n -> CryptoUtils.randomBytes(n) },
                    ),
                )
            }

            is RsaKeyWrapped -> {
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
