package io.github.aeshen.observability.sink.registry

import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.config.sink.File
import io.github.aeshen.observability.config.sink.Http
import io.github.aeshen.observability.config.sink.OpenTelemetry
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.config.sink.Slf4j
import io.github.aeshen.observability.config.sink.ZipFile
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.impl.ConsoleObservabilitySink
import io.github.aeshen.observability.sink.impl.FileObservabilitySink
import io.github.aeshen.observability.sink.impl.HttpObservabilitySink
import io.github.aeshen.observability.sink.impl.OpenTelemetryObservabilitySink
import io.github.aeshen.observability.sink.impl.Slf4JObservabilitySink
import io.github.aeshen.observability.sink.impl.ZipFileObservabilitySink

class SinkRegistry private constructor(
    private val providers: List<SinkProvider>,
) {
    fun resolve(config: SinkConfig): ObservabilitySink =
        providers.firstNotNullOfOrNull { provider ->
            provider.create(config)
        } ?: error("No SinkProvider registered for sink config type: ${config::class.qualifiedName}")

    fun resolveAll(configs: List<SinkConfig>): List<ObservabilitySink> = configs.map { resolve(it) }

    fun toBuilder(): Builder = Builder(providers.toMutableList())

    class Builder internal constructor(
        private val providers: MutableList<SinkProvider> = mutableListOf(),
    ) {
        fun registerProvider(provider: SinkProvider): Builder = apply { providers += provider }

        inline fun <reified T : SinkConfig> register(noinline create: (T) -> ObservabilitySink): Builder =
            apply {
                registerProvider { config ->
                    if (config is T) {
                        create(config)
                    } else {
                        null
                    }
                }
            }

        fun <T : SinkConfig> register(
            type: Class<T>,
            factory: java.util.function.Function<T, ObservabilitySink>,
        ): Builder =
            apply {
                registerProvider { config ->
                    if (type.isInstance(config)) {
                        factory.apply(type.cast(config))
                    } else {
                        null
                    }
                }
            }

        fun build(): SinkRegistry = SinkRegistry(providers = providers.toList())
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun defaultBuilder(): Builder = Builder(mutableListOf(BuiltInSinkProvider))

        @JvmStatic
        fun empty(): SinkRegistry = builder().build()

        @JvmStatic
        @JvmName("getDefault")
        fun default(): SinkRegistry = defaultBuilder().build()
    }
}

private object BuiltInSinkProvider : SinkProvider {
    override fun create(config: SinkConfig): ObservabilitySink? =
        when (config) {
            is Console -> ConsoleObservabilitySink()
            is Slf4j -> createOptionalSink("SLF4J") { Slf4JObservabilitySink(config.logger) }
            is File -> FileObservabilitySink(config.path)
            is Http -> HttpObservabilitySink(config)
            is ZipFile -> ZipFileObservabilitySink(config.path)
            is OpenTelemetry ->
                createOptionalSink("OpenTelemetry OTLP") {
                    OpenTelemetryObservabilitySink.fromConfig(config)
                }
            else -> null
        }

    private fun createOptionalSink(
        integrationName: String,
        create: () -> ObservabilitySink,
    ): ObservabilitySink =
        try {
            create()
        } catch (e: NoClassDefFoundError) {
            throw IllegalStateException(
                "$integrationName sink requires optional runtime dependencies. " +
                    "Add the integration dependencies to your application classpath.",
                e,
            )
        }
}
