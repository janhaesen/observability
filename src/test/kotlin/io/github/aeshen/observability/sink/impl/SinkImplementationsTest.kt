package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.key.LongKey
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SinkImplementationsTest {
    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("http/request done"),
    }

    @Test
    fun `file sink appends bytes`() {
        val dir = Files.createTempDirectory("obs-file-sink")
        val path = dir.resolve("events.jsonl")
        val sink = FileObservabilitySink(path)

        sink.handle(encoded("one\n"))
        sink.handle(encoded("two\n"))

        val content = Files.readString(path)
        assertEquals("one\ntwo\n", content)
    }

    @Test
    fun `zip sink writes entries with sanitized event name`() {
        val dir = Files.createTempDirectory("obs-zip-sink")
        val zipPath = dir.resolve("events.zip")
        val sink = ZipFileObservabilitySink(zipPath)

        sink.handle(encoded("payload", mutableMapOf("event" to TestEvent.TEST.resolvedName())))
        sink.close()

        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            val entry = zis.nextEntry
            assertNotNull(entry)
            assertTrue(entry.name.contains("http_request_done"))
            val bytes = zis.readBytes()
            assertEquals("payload", bytes.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `otel sink maps event fields to otel log record`() {
        val exporter = InMemoryLogRecordExporter.create()
        val loggerProvider =
            SdkLoggerProvider
                .builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()
        val openTelemetry =
            OpenTelemetrySdk
                .builder()
                .setLoggerProvider(loggerProvider)
                .build()
        val sink = OpenTelemetryObservabilitySink(openTelemetry)
        val boom = IllegalStateException("boom")
        val event =
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.WARN,
                message = "Request completed",
                context =
                    ObservabilityContext
                        .builder()
                        .put(StringKey.REQUEST_ID, "req-123")
                        .put(LongKey.STATUS_CODE, 200L)
                        .build(),
                error = boom,
            )

        sink.handle(
            EncodedEvent(
                original = event,
                encoded = "payload".toByteArray(),
                metadata =
                    mutableMapOf(
                        "event" to TestEvent.TEST.resolvedName(),
                        "size" to 7,
                    ),
            ),
        )

        val exported = exporter.finishedLogRecordItems
        assertEquals(1, exported.size)

        val record = exported.single()
        assertEquals(Severity.WARN, record.severity)
        assertEquals("Request completed", record.bodyValue?.asString())
        assertEquals(
            TestEvent.TEST.resolvedName(),
            record.attributes.get(AttributeKey.stringKey("observability.event_name")),
        )
        assertEquals("req-123", record.attributes.get(AttributeKey.stringKey("context.id")))
        assertEquals(200L, record.attributes.get(AttributeKey.longKey("context.status_code")))
        assertEquals(
            TestEvent.TEST.resolvedName(),
            record.attributes.get(AttributeKey.stringKey("meta.event")),
        )
        assertEquals("boom", record.attributes.get(AttributeKey.stringKey("exception.message")))
        assertNotNull(record.attributes.get(AttributeKey.stringKey("exception.stacktrace")))

        loggerProvider.shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun encoded(
        text: String,
        metadata: MutableMap<String, Any?> = mutableMapOf(),
    ): EncodedEvent {
        val event =
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.INFO,
                context = ObservabilityContext.empty(),
            )
        return EncodedEvent(
            original = event,
            encoded = text.toByteArray(),
            metadata = metadata,
        )
    }
}
