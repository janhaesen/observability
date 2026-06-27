package io.github.aeshen.observability.spring

import io.github.aeshen.observability.ContextProvider
import io.github.aeshen.observability.Observability
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.codec.impl.JsonLineCodec
import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.enricher.MetadataEnricher
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.decorator.AsyncObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkProvider
import io.github.aeshen.observability.sink.registry.SinkRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration
@EnableConfigurationProperties(ObservabilityProperties::class)
class ObservabilityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun observabilityDiagnostics(): ObservabilityDiagnostics {
        return InMemoryOperationalDiagnostics()
    }

    @Bean
    @ConditionalOnMissingBean
    fun observabilityCodec(): ObservabilityCodec {
        return JsonLineCodec()
    }

    @Bean
    @ConditionalOnMissingBean
    fun sinkRegistry(
        customProviders: List<SinkProvider>,
        properties: ObservabilityProperties,
        diagnostics: ObservabilityDiagnostics,
    ): SinkRegistry {
        val builder = SinkRegistry.builder()

        fun wrapIfAsync(sink: ObservabilitySink): ObservabilitySink {
            return if (properties.async.enabled) {
                AsyncObservabilitySink(
                    delegate = sink,
                    capacity = properties.async.capacity,
                    offerTimeoutMillis = properties.async.offerTimeoutMillis,
                    failOnDrop = properties.async.failOnDrop,
                    closeTimeoutMillis = properties.async.closeTimeoutMillis,
                    shutdownPolicy = properties.async.shutdownPolicy,
                    diagnostics = diagnostics,
                )
            } else {
                sink
            }
        }

        customProviders.forEach { customProvider ->
            builder.registerProvider { config ->
                val sink = customProvider.create(config)
                sink?.let { wrapIfAsync(it) }
            }
        }

        val defaultRegistry = SinkRegistry.default()
        builder.registerProvider { config ->
            val sink =
                try {
                    defaultRegistry.resolve(config)
                } catch (
                    @Suppress(
                        "TooGenericExceptionCaught",
                        "SwallowedException",
                        "detekt.TooGenericExceptionCaught",
                        "detekt.SwallowedException",
                    ) e: Exception,
                ) {
                    null
                }
            sink?.let { wrapIfAsync(it) }
        }

        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    @Suppress("LongParameterList", "detekt.LongParameterList")
    fun observability(
        properties: ObservabilityProperties,
        codec: ObservabilityCodec,
        contextProviders: List<ContextProvider>,
        metadataEnrichers: List<MetadataEnricher>,
        processors: List<ObservabilityProcessor>,
        sinkRegistry: SinkRegistry,
        diagnostics: ObservabilityDiagnostics,
    ): Observability {
        val sinkConfigs = properties.sinks.map { it.toSinkConfig() }

        val config =
            ObservabilityFactory.Config(
                sinks = sinkConfigs,
                encryption = properties.encryption?.toEncryptionConfig(),
                failOnSinkError = properties.failOnSinkError,
                sinkRegistry = sinkRegistry,
                codec = codec,
                contextProviders = contextProviders,
                metadataEnrichers = metadataEnrichers,
                diagnostics = diagnostics,
                profile = properties.profile,
                processors = processors,
            )

        return ObservabilityFactory.create(config)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator::class)
    class ActuatorConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = ["observabilityHealthIndicator"])
        fun observabilityHealthIndicator(diagnostics: ObservabilityDiagnostics): HealthIndicator {
            return ObservabilityHealthIndicator(diagnostics)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry::class)
    class MicrometerConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun observabilityMetricsBinder(diagnostics: ObservabilityDiagnostics): ObservabilityMetricsBinder {
            return ObservabilityMetricsBinder(diagnostics)
        }
    }
}
