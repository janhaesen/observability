package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val DEFAULT_INSTRUMENTATION_SCOPE = "io.github.aeshen.observability"
private const val DEFAULT_CLOSE_TIMEOUT_SECONDS = 10L
private const val OBSERVABILITY_EVENT_NAME_KEY = "observability.event_name"
private const val OBSERVABILITY_LEVEL_KEY = "observability.level"
private const val OBSERVABILITY_PAYLOAD_SIZE_KEY = "observability.payload_size"
private const val CONTEXT_PREFIX = "context."
private const val META_PREFIX = "meta."
private const val SERVICE_NAME_KEY = "service.name"
private const val SERVICE_VERSION_KEY = "service.version"
private const val EXCEPTION_TYPE_KEY = "exception.type"
private const val EXCEPTION_MESSAGE_KEY = "exception.message"
private const val EXCEPTION_STACKTRACE_KEY = "exception.stacktrace"

internal class OpenTelemetryObservabilitySink internal constructor(
    private val logger: Logger,
    private val closeAction: (() -> Unit)? = null,
) : ObservabilitySink {
    internal constructor(
        openTelemetry: OpenTelemetry,
        instrumentationScopeName: String = DEFAULT_INSTRUMENTATION_SCOPE,
        closeAction: (() -> Unit)? = null,
    ) : this(
        logger = openTelemetry.logsBridge.loggerBuilder(instrumentationScopeName).build(),
        closeAction = closeAction,
    )

    override fun handle(event: EncodedEvent) {
        logger
            .logRecordBuilder()
            .setSeverity(event.original.level.toSeverity())
            .setBody(event.original.message ?: event.original.name.resolvedName())
            .setTimestamp(event.original.timestamp.toEpochMilliseconds(), TimeUnit.MILLISECONDS)
            .setAllAttributes(event.toAttributes())
            .emit()
    }

    override fun close() {
        closeAction?.invoke()
    }

    companion object {
        fun fromConfig(config: io.github.aeshen.observability.config.sink.OpenTelemetry): OpenTelemetryObservabilitySink {
            val exporterBuilder = OtlpHttpLogRecordExporter.builder().setEndpoint(config.endpoint)
            config.headers.forEach { (key, value) -> exporterBuilder.addHeader(key, value) }

            val resourceBuilder =
                Resource
                    .builder()
                    .put(AttributeKey.stringKey(SERVICE_NAME_KEY), config.serviceName)

            config.serviceVersion
                ?.takeIf { it.isNotBlank() }
                ?.let { resourceBuilder.put(AttributeKey.stringKey(SERVICE_VERSION_KEY), it) }

            val loggerProvider =
                SdkLoggerProvider
                    .builder()
                    .setResource(Resource.getDefault().merge(resourceBuilder.build()))
                    .addLogRecordProcessor(
                        BatchLogRecordProcessor
                            .builder(exporterBuilder.build())
                            .setScheduleDelay(Duration.ofMillis(config.scheduleDelayMillis))
                            .setExporterTimeout(Duration.ofMillis(config.exporterTimeoutMillis))
                            .setMaxQueueSize(config.maxQueueSize)
                            .setMaxExportBatchSize(config.maxExportBatchSize)
                            .build(),
                    )
                    .build()

            val openTelemetry =
                OpenTelemetrySdk
                    .builder()
                    .setLoggerProvider(loggerProvider)
                    .build()

            return OpenTelemetryObservabilitySink(
                openTelemetry = openTelemetry,
                instrumentationScopeName = config.instrumentationScopeName,
            ) {
                loggerProvider.shutdown().join(DEFAULT_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }
}

private fun EncodedEvent.toAttributes(): Attributes {
    val builder = Attributes.builder()

    putAttribute(builder, OBSERVABILITY_EVENT_NAME_KEY, original.name.resolvedName())
    putAttribute(builder, OBSERVABILITY_LEVEL_KEY, original.level.name)
    original.payload?.let { putAttribute(builder, OBSERVABILITY_PAYLOAD_SIZE_KEY, it.size.toLong()) }

    original.context.asMap().forEach { (key, value) ->
        putAttribute(builder, "$CONTEXT_PREFIX${key.keyName}", value)
    }

    metadata.forEach { (key, value) ->
        putAttribute(builder, "$META_PREFIX$key", value)
    }

    original.error?.let { throwable ->
        putAttribute(builder, EXCEPTION_TYPE_KEY, throwable.javaClass.name)
        throwable.message?.let { putAttribute(builder, EXCEPTION_MESSAGE_KEY, it) }
        putAttribute(builder, EXCEPTION_STACKTRACE_KEY, throwable.stackTraceToString())
    }

    return builder.build()
}

private fun putAttribute(
    builder: AttributesBuilder,
    key: String,
    value: Any?,
) {
    when (value) {
        null -> Unit
        is String -> builder.put(AttributeKey.stringKey(key), value)
        is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
        is Long -> builder.put(AttributeKey.longKey(key), value)
        is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
        is Short -> builder.put(AttributeKey.longKey(key), value.toLong())
        is Byte -> builder.put(AttributeKey.longKey(key), value.toLong())
        is Double -> builder.put(AttributeKey.doubleKey(key), value)
        is Float -> builder.put(AttributeKey.doubleKey(key), value.toDouble())
        else -> builder.put(AttributeKey.stringKey(key), value.toString())
    }
}

private fun EventLevel.toSeverity(): Severity =
    when (this) {
        EventLevel.TRACE -> Severity.TRACE
        EventLevel.DEBUG -> Severity.DEBUG
        EventLevel.INFO -> Severity.INFO
        EventLevel.WARN -> Severity.WARN
        EventLevel.ERROR -> Severity.ERROR
    }
