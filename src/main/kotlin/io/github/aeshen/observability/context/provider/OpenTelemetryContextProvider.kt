package io.github.aeshen.observability.context.provider

import io.github.aeshen.observability.ContextProvider
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.key.TypedKey
import io.opentelemetry.api.trace.Span

/**
 * A [ContextProvider] that reads the current OpenTelemetry trace/span context and attaches
 * correlation identifiers to every emitted event.
 *
 * ### Keys injected
 * | Context key      | Value                          |
 * |------------------|--------------------------------|
 * | `trace.id`       | 32-char lowercase hex trace ID |
 * | `trace.span_id`  | 16-char lowercase hex span ID  |
 * | `trace.flags`    | 2-char hex trace-flags byte    |
 *
 * ### Availability
 * `opentelemetry-api` must be on the runtime classpath and a valid span must be active on the
 * current thread. If no valid span context is found, an empty context is returned silently.
 *
 * ### Merge precedence
 * Context providers are applied before event-level context. If an event explicitly sets a key
 * that matches one of the keys above, the **event-level value wins**.
 *
 * ### Example setup
 * ```kotlin
 * val observability = ObservabilityFactory.create(
 *     ObservabilityFactory.Config(
 *         sinks = listOf(/* ... */),
 *         contextProviders = listOf(OpenTelemetryContextProvider()),
 *     )
 * )
 * ```
 *
 * With the provider registered, trace and span IDs are automatically correlated on every event
 * emitted inside an active OTel span:
 * ```kotlin
 * val span = tracer.spanBuilder("processOrder").startSpan()
 * span.makeCurrent().use {
 *     observability.info(MyEvent.ORDER_PLACED)
 *     // → event context contains trace.id, trace.span_id, trace.flags
 * }
 * span.end()
 * ```
 */
class OpenTelemetryContextProvider : ContextProvider {

    override fun provide(): ObservabilityContext {
        val spanContext = Span.current().spanContext
        if (!spanContext.isValid) return ObservabilityContext.empty()

        return ObservabilityContext.builder()
            .put(OtelKey.TRACE_ID, spanContext.traceId)
            .put(OtelKey.SPAN_ID, spanContext.spanId)
            .put(OtelKey.TRACE_FLAGS, spanContext.traceFlags.asHex())
            .build()
    }

    /**
     * Typed keys used by [OpenTelemetryContextProvider] to attach OTel correlation data.
     */
    enum class OtelKey(override val keyName: String) : TypedKey<String> {
        TRACE_ID("trace.id"),
        SPAN_ID("trace.span_id"),
        TRACE_FLAGS("trace.flags"),
    }
}

