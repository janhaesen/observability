package io.github.aeshen.observability.sink.impl

import com.sun.net.httpserver.HttpServer
import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Http
import io.github.aeshen.observability.config.sink.HttpMethod
import io.github.aeshen.observability.key.LongKey
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SinkImplementationsTest {
    private class CapturedHttpRequest(
        val method: String,
        val path: String,
        val contentType: String?,
        val body: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CapturedHttpRequest

            if (method != other.method) return false
            if (path != other.path) return false
            if (contentType != other.contentType) return false
            if (!body.contentEquals(other.body)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = method.hashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + (contentType?.hashCode() ?: 0)
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("http/request done"),
    }

    @Test
    fun `console sink writes encoded payload as line output`() {
        val sink = ConsoleObservabilitySink()
        val captured = ByteArrayOutputStream()
        val previous = System.out

        try {
            System.setOut(PrintStream(captured, true, Charsets.UTF_8))
            sink.handle(encoded("{\"name\":\"test\"}\n"))
        } finally {
            System.setOut(previous)
        }

        assertEquals("{\"name\":\"test\"}\n", captured.toString(Charsets.UTF_8))
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
    fun `file sink rejects writes after close`() {
        val dir = Files.createTempDirectory("obs-file-sink-close")
        val path = dir.resolve("events.jsonl")
        val sink = FileObservabilitySink(path)

        sink.handle(encoded("one\n"))
        sink.close()

        assertFailsWith<IllegalStateException> {
            sink.handle(encoded("two\n"))
        }
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
    fun `zip sink preserves existing entries across reopen`() {
        val dir = Files.createTempDirectory("obs-zip-sink-reopen")
        val zipPath = dir.resolve("events.zip")

        ZipFileObservabilitySink(zipPath).use { sink ->
            sink.handle(encoded("first", mutableMapOf("event" to "evt.one")))
        }

        ZipFileObservabilitySink(zipPath).use { sink ->
            sink.handle(encoded("second", mutableMapOf("event" to "evt.two")))
        }

        val names = mutableListOf<String>()
        val payloads = mutableListOf<String>()
        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                names += entry.name
                payloads += zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }

        assertEquals(2, names.size)
        assertEquals(listOf("first", "second"), payloads)
        assertTrue(names[0].startsWith("log-000000000001"))
        assertTrue(names[1].startsWith("log-000000000002"))
    }

    @Test
    fun `http sink posts encoded payload with configured method and headers`() {
        val requests = LinkedBlockingQueue<CapturedHttpRequest>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/ingest") { exchange ->
                    requests.offer(
                        CapturedHttpRequest(
                            method = exchange.requestMethod,
                            path = exchange.requestURI.path,
                            contentType = exchange.requestHeaders.getFirst("Content-Type"),
                            body = exchange.requestBody.use { it.readBytes() },
                        ),
                    )
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                start()
            }

        try {
            val sink =
                HttpObservabilitySink(
                    Http(
                        endpoint = "http://127.0.0.1:${server.address.port}/ingest",
                        method = HttpMethod.PUT,
                        headers = mapOf("Content-Type" to "application/json"),
                    ),
                )

            sink.handle(encoded("{\"status\":\"ok\"}\n"))

            val captured = requests.poll(5, TimeUnit.SECONDS)
            assertNotNull(captured)
            assertEquals("PUT", captured.method)
            assertEquals("/ingest", captured.path)
            assertEquals("application/json", captured.contentType)
            assertEquals("{\"status\":\"ok\"}\n", captured.body.toString(Charsets.UTF_8))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http sink throws illegal state exception on non success response`() {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/ingest") { exchange ->
                    exchange.requestBody.use { it.readBytes() }
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                }
                start()
            }

        try {
            val sink =
                HttpObservabilitySink(
                    Http(
                        endpoint = "http://127.0.0.1:${server.address.port}/ingest",
                        method = HttpMethod.POST,
                    ),
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    sink.handle(encoded("failed"))
                }

            assertTrue(error.message.orEmpty().contains("status=503"))
        } finally {
            server.stop(0)
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

        loggerProvider.shutdown().join(5, TimeUnit.SECONDS)
    }

    @Test
    fun `otel sink surfaces close action failures`() {
        val openTelemetry =
            OpenTelemetrySdk
                .builder()
                .setLoggerProvider(SdkLoggerProvider.builder().build())
                .build()

        val sink =
            OpenTelemetryObservabilitySink(
                openTelemetry = openTelemetry,
            ) {
                error("shutdown failed")
            }

        assertFailsWith<IllegalStateException> {
            sink.close()
        }
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
