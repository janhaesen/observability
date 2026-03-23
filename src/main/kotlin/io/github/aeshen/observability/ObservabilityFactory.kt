package io.github.aeshen.observability

import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.codec.impl.JsonLineCodec
import io.github.aeshen.observability.config.encryption.AesGcm
import io.github.aeshen.observability.config.encryption.EncryptionConfig
import io.github.aeshen.observability.config.encryption.RsaKeyWrapped
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.enricher.MetadataEnricher
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.processor.encryption.EncryptingObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.decorator.BatchingObservabilitySink
import io.github.aeshen.observability.sink.decorator.RetryingObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry
import io.github.aeshen.observability.util.CryptoUtils
import javax.crypto.spec.SecretKeySpec

object ObservabilityFactory {
    private const val BYTE_16 = 16
    private const val BYTE_24 = 24
    private const val BYTE_32 = 32
    private const val AUDIT_MAX_ATTEMPTS = 5
    private const val AUDIT_MAX_BATCH_SIZE = 100
    private const val AUDIT_FLUSH_INTERVAL_MILLIS = 250L
    private const val LEGACY_CREATE_DEPRECATION_MESSAGE =
        "Use create(Config) with SinkRegistry-based sink wiring. " +
            "This overload is kept for backward compatibility."

    enum class Profile {
        STANDARD,
        AUDIT_DURABLE,
    }

    data class Config(
        val sinks: List<SinkConfig>,
        val encryption: EncryptionConfig? = null,
        val failOnSinkError: Boolean = false,
        val sinkRegistry: SinkRegistry = SinkRegistry.default(),
        val codec: ObservabilityCodec = JsonLineCodec(),
        val contextProviders: List<ContextProvider> = emptyList(),
        val metadataEnrichers: List<MetadataEnricher> = emptyList(),
        val diagnostics: ObservabilityDiagnostics = ObservabilityDiagnostics.NOOP,
        val profile: Profile = Profile.STANDARD,
        val processors: List<ObservabilityProcessor> = emptyList(),
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
        val sinks: List<ObservabilitySink> =
            applyProfile(
                buildSinks(config),
                config.profile,
                config.diagnostics,
            )

        require(sinks.isNotEmpty()) { "At least one sink must be configured." }

        val resolvedProcessors: List<ObservabilityProcessor> =
            buildProcessors(config.processors, config.encryption)

        return ObservabilityPipeline(
            codec = config.codec,
            contextProviders = config.contextProviders,
            metadataEnrichers = config.metadataEnrichers,
            processors = resolvedProcessors,
            sinks = sinks,
            failOnSinkError = resolveFailOnSinkError(config.failOnSinkError, config.profile),
            diagnostics = config.diagnostics,
        )
    }

    /**
     * Legacy runtime-sink convenience API.
     *
     * Prefer [create] with [Config] and explicit [SinkRegistry] wiring.
     */
    @Deprecated(
        message = LEGACY_CREATE_DEPRECATION_MESSAGE,
    )
    fun create(
        vararg sinks: ObservabilitySink,
        encryption: EncryptionConfig? = null,
        failOnSinkError: Boolean = false,
        codec: ObservabilityCodec = JsonLineCodec(),
        contextProviders: List<ContextProvider> = emptyList(),
        metadataEnrichers: List<MetadataEnricher> = emptyList(),
        diagnostics: ObservabilityDiagnostics = ObservabilityDiagnostics.NOOP,
        profile: Profile = Profile.STANDARD,
    ): Observability {
        val registry =
            SinkRegistry
                .builder()
                .register<LegacyDirectSinkConfig> { cfg -> cfg.sink }
                .build()

        return create(
            Config(
                sinks = sinks.map { sink -> LegacyDirectSinkConfig(sink) },
                encryption = encryption,
                failOnSinkError = failOnSinkError,
                sinkRegistry = registry,
                codec = codec,
                contextProviders = contextProviders,
                metadataEnrichers = metadataEnrichers,
                diagnostics = diagnostics,
                profile = profile,
            ),
        )
    }

    private fun buildSinks(config: Config): List<ObservabilitySink> =
        config.sinkRegistry.resolveAll(config.sinks)

    private fun buildProcessors(
        customProcessors: List<ObservabilityProcessor>,
        encryption: EncryptionConfig?,
    ): List<ObservabilityProcessor> = customProcessors + buildEncryptionProcessors(encryption)

    private fun buildEncryptionProcessors(encryption: EncryptionConfig?): List<ObservabilityProcessor> =
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

    private fun resolveFailOnSinkError(
        explicit: Boolean,
        profile: Profile,
    ): Boolean = if (profile == Profile.AUDIT_DURABLE) true else explicit

    private data class LegacyDirectSinkConfig(
        val sink: ObservabilitySink,
    ) : SinkConfig

    private fun applyProfile(
        sinks: List<ObservabilitySink>,
        profile: Profile,
        diagnostics: ObservabilityDiagnostics,
    ): List<ObservabilitySink> =
        when (profile) {
            Profile.STANDARD -> {
                sinks
            }

            Profile.AUDIT_DURABLE -> {
                sinks.map { sink ->
                    BatchingObservabilitySink(
                        delegate = RetryingObservabilitySink(
                            delegate = sink,
                            maxAttempts = AUDIT_MAX_ATTEMPTS,
                            diagnostics = diagnostics,
                        ),
                        maxBatchSize = AUDIT_MAX_BATCH_SIZE,
                        flushIntervalMillis = AUDIT_FLUSH_INTERVAL_MILLIS,
                        diagnostics = diagnostics,
                    )
                }
            }
        }
}
