package io.github.aeshen.observability.context.provider

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenTelemetryContextProviderTest {
    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var sdk: OpenTelemetrySdk

    @BeforeTest
    fun setUp() {
        spanExporter = InMemorySpanExporter.create()
        val tracerProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()
        sdk =
            OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()
    }

    @AfterTest
    fun tearDown() {
        sdk.close()
        spanExporter.reset()
    }

    @Test
    fun `returns empty context when no active span`() {
        val context = OpenTelemetryContextProvider().provide()
        assertTrue(context.asMap().isEmpty())
    }

    @Test
    fun `returns empty context for invalid span context`() {
        val invalidSpan =
            Span.wrap(
                SpanContext.create(
                    "00000000000000000000000000000000",
                    "0000000000000000",
                    TraceFlags.getDefault(),
                    TraceState.getDefault(),
                ),
            )
        invalidSpan.makeCurrent().use {
            val context = OpenTelemetryContextProvider().provide()
            assertTrue(context.asMap().isEmpty())
        }
    }

    @Test
    fun `injects trace id span id and flags when span is active`() {
        val tracer = sdk.getTracer("test")
        val span = tracer.spanBuilder("test-span").startSpan()

        span.makeCurrent().use {
            val context = OpenTelemetryContextProvider().provide()
            val map = context.asMap()

            val keys = map.keys.map { it.keyName }.toSet()
            assertEquals(
                setOf(
                    OpenTelemetryContextProvider.OtelKey.TRACE_ID.keyName,
                    OpenTelemetryContextProvider.OtelKey.SPAN_ID.keyName,
                    OpenTelemetryContextProvider.OtelKey.TRACE_FLAGS.keyName,
                ),
                keys,
            )

            val spanContext = Span.current().spanContext
            assertEquals(spanContext.traceId, context.get(OpenTelemetryContextProvider.OtelKey.TRACE_ID))
            assertEquals(spanContext.spanId, context.get(OpenTelemetryContextProvider.OtelKey.SPAN_ID))
            assertEquals(spanContext.traceFlags.asHex(), context.get(OpenTelemetryContextProvider.OtelKey.TRACE_FLAGS))
        }

        span.end()
    }

    @Test
    fun `trace id is 32 char lowercase hex`() {
        val tracer = sdk.getTracer("test")
        val span = tracer.spanBuilder("hex-check").startSpan()

        span.makeCurrent().use {
            val traceId =
                OpenTelemetryContextProvider().provide()
                    .get(OpenTelemetryContextProvider.OtelKey.TRACE_ID)
            assertEquals(32, traceId?.length)
            assertTrue(traceId!!.all { it.isDigit() || it in 'a'..'f' })
        }

        span.end()
    }

    @Test
    fun `span id is 16 char lowercase hex`() {
        val tracer = sdk.getTracer("test")
        val span = tracer.spanBuilder("span-hex-check").startSpan()

        span.makeCurrent().use {
            val spanId =
                OpenTelemetryContextProvider().provide()
                    .get(OpenTelemetryContextProvider.OtelKey.SPAN_ID)
            assertEquals(16, spanId?.length)
            assertTrue(spanId!!.all { it.isDigit() || it in 'a'..'f' })
        }

        span.end()
    }

    @Test
    fun `returns empty context after span scope closes`() {
        val tracer = sdk.getTracer("test")
        val span = tracer.spanBuilder("ended").startSpan()
        span.makeCurrent().use { }
        span.end()

        val context = OpenTelemetryContextProvider().provide()
        assertTrue(context.asMap().isEmpty())
    }

    @Test
    fun `otel key names match expected values`() {
        assertEquals("trace.id", OpenTelemetryContextProvider.OtelKey.TRACE_ID.keyName)
        assertEquals("trace.span_id", OpenTelemetryContextProvider.OtelKey.SPAN_ID.keyName)
        assertEquals("trace.flags", OpenTelemetryContextProvider.OtelKey.TRACE_FLAGS.keyName)
    }
}
