package io.github.aeshen.observability.sink.impl

import com.sun.net.httpserver.HttpServer
import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Http
import io.github.aeshen.observability.config.sink.HttpMethod
import io.github.aeshen.observability.config.sink.Webhook
import io.github.aeshen.observability.key.LongKey
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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

    @Test
    fun `kafka sink sends encoded payload to correct topic with event name as key`() {
        val mockProducer = MockProducer(true, StringSerializer(), ByteArraySerializer())
        val sink = KafkaObservabilitySink(mockProducer, "my-topic", 5_000)

        sink.handle(encoded("payload", mutableMapOf("event" to "order.created")))

        assertEquals(1, mockProducer.history().size)
        val record = mockProducer.history().single()
        assertEquals("my-topic", record.topic())
        assertEquals("order.created", record.key())
        assertEquals("payload", record.value().toString(Charsets.UTF_8))
    }

    @Test
    fun `kafka sink falls back to default key when metadata has no event entry`() {
        val mockProducer = MockProducer(true, StringSerializer(), ByteArraySerializer())
        val sink = KafkaObservabilitySink(mockProducer, "my-topic", 5_000)

        sink.handle(encoded("no-event-key"))

        assertEquals("observability", mockProducer.history().single().key())
    }

    @Test
    fun `kafka sink surfaces send failure as illegal state exception`() {
        val mockProducer = MockProducer(false, StringSerializer(), ByteArraySerializer())
        val sink = KafkaObservabilitySink(mockProducer, "my-topic", 5_000)

        mockProducer.errorNext(KafkaException("broker unavailable"))

        assertFailsWith<IllegalStateException> {
            sink.handle(encoded("payload"))
        }
    }

    @Test
    fun `kafka sink sends multiple events in order`() {
        val mockProducer = MockProducer(true, StringSerializer(), ByteArraySerializer())
        val sink = KafkaObservabilitySink(mockProducer, "events", 5_000)

        sink.handle(encoded("first", mutableMapOf("event" to "evt.a")))
        sink.handle(encoded("second", mutableMapOf("event" to "evt.b")))

        val history = mockProducer.history()
        assertEquals(2, history.size)
        assertEquals("first", history[0].value().toString(Charsets.UTF_8))
        assertEquals("second", history[1].value().toString(Charsets.UTF_8))
    }

    @Test
    fun `webhook sink posts payload with hmac signature header`() {
        val secret = "my-secret"
        val requests = LinkedBlockingQueue<CapturedHttpRequest>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/hook") { exchange ->
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
            val config =
                Webhook(
                    endpoint = "http://127.0.0.1:${server.address.port}/hook",
                    secret = secret,
                    headers = mapOf("Content-Type" to "application/json"),
                )
            val sink = WebhookObservabilitySink(config)
            val payload = "{\"event\":\"test\"}\n".toByteArray()
            sink.handle(encoded("{\"event\":\"test\"}\n"))

            val captured = requests.poll(5, TimeUnit.SECONDS)
            assertNotNull(captured)
            assertEquals("POST", captured.method)

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expectedSig = "sha256=" + mac.doFinal(payload).joinToString("") { "%02x".format(it) }

            // Verify the captured request body matches what was signed
            assertEquals(expectedSig, hmacHeader(captured.body, secret))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `webhook sink throws on non-success response`() {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/hook") { exchange ->
                    exchange.requestBody.use { it.readBytes() }
                    exchange.sendResponseHeaders(400, -1)
                    exchange.close()
                }
                start()
            }

        try {
            val sink =
                WebhookObservabilitySink(
                    Webhook(
                        endpoint = "http://127.0.0.1:${server.address.port}/hook",
                        secret = "s",
                    ),
                )
            val error = assertFailsWith<IllegalStateException> { sink.handle(encoded("body")) }
            assertTrue(error.message.orEmpty().contains("status=400"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `s3 sink uploads single event as gzipped jsonl`() {
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val sink = S3ObservabilitySink(S3Uploader { key, bytes -> uploads += key to bytes }, "logs/")

        sink.handle(encoded("{\"name\":\"test\"}\n"))

        assertEquals(1, uploads.size)
        val (key, bytes) = uploads.single()
        assertTrue(key.startsWith("logs/"))
        assertTrue(key.endsWith(".jsonl.gz"))
        val decompressed = GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertTrue(decompressed.contains("{\"name\":\"test\"}"))
    }

    @Test
    fun `s3 sink uploads batch as single gzipped file`() {
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val sink = S3ObservabilitySink(S3Uploader { key, bytes -> uploads += key to bytes }, "obs/")

        sink.handleBatch(listOf(encoded("first\n"), encoded("second\n")))

        assertEquals(1, uploads.size)
        val decompressed = GZIPInputStream(uploads.single().second.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertTrue(decompressed.contains("first"))
        assertTrue(decompressed.contains("second"))
    }

    @Test
    fun `s3 sink ignores empty batch`() {
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val sink = S3ObservabilitySink(S3Uploader { key, bytes -> uploads += key to bytes }, "obs/")

        sink.handleBatch(emptyList())

        assertEquals(0, uploads.size)
    }

    @Test
    fun `redis sink publishes event with event name and payload`() {
        val published = mutableListOf<Pair<String, String>>()
        val sink = RedisObservabilitySink(RedisPublisher { eventName, payload -> published += eventName to payload })

        sink.handle(encoded("{\"x\":1}\n", mutableMapOf("event" to "order.placed")))

        assertEquals(1, published.size)
        assertEquals("order.placed", published.single().first)
        assertEquals("{\"x\":1}\n", published.single().second)
    }

    @Test
    fun `redis sink falls back to default key when no event in metadata`() {
        val published = mutableListOf<Pair<String, String>>()
        val sink = RedisObservabilitySink(RedisPublisher { eventName, payload -> published += eventName to payload })

        sink.handle(encoded("payload"))

        assertEquals("observability", published.single().first)
    }

    private fun hmacHeader(
        body: ByteArray,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
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
