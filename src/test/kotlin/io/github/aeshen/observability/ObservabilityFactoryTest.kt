package io.github.aeshen.observability

import com.sun.net.httpserver.HttpServer
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.OpenTelemetry
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkProvider
import io.github.aeshen.observability.sink.registry.SinkRegistry
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObservabilityFactoryTest {
    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("otel.factory.test"),
    }

    @Test
    fun `factory rejects empty sink configuration`() {
        assertFailsWith<IllegalArgumentException> {
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = emptyList(),
                ),
            )
        }
    }

    @Test
    fun `aes key helper accepts 16 24 and 32 byte keys`() {
        ObservabilityFactory.Config.aesGcmFromRawKeyBytes(ByteArray(16))
        ObservabilityFactory.Config.aesGcmFromRawKeyBytes(ByteArray(24))
        ObservabilityFactory.Config.aesGcmFromRawKeyBytes(ByteArray(32))
    }

    @Test
    fun `aes key helper rejects invalid length`() {
        assertFailsWith<IllegalArgumentException> {
            ObservabilityFactory.Config.aesGcmFromRawKeyBytes(ByteArray(20))
        }
    }

    @Test
    fun `factory otel sink exports logs over otlp http`() {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/logs") { exchange ->
                    val body = exchange.requestBody.use { it.readBytes() }
                    requests.offer(
                        CapturedRequest(
                            method = exchange.requestMethod,
                            path = exchange.requestURI.path,
                            contentType = exchange.requestHeaders.getFirst("Content-Type"),
                            body = body,
                        ),
                    )
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }

        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/v1/logs"
            val observability =
                ObservabilityFactory.create(
                    ObservabilityFactory.Config(
                        sinks =
                            listOf(
                                OpenTelemetry(
                                    endpoint = endpoint,
                                    serviceName = "observability-test",
                                ),
                            ),
                    ),
                )

            observability.use {
                it.info(
                    name = TestEvent.TEST,
                    message = "sent to collector",
                )
            }

            val request = requests.poll(10, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("POST", request.method)
            assertEquals("/v1/logs", request.path)
            assertTrue(request.body.isNotEmpty())
            assertEquals("application/x-protobuf", request.contentType)
        } finally {
            server.stop(0)
        }
    }


    @Test
    fun `factory supports direct sink instance list`() {
        val seen = mutableListOf<EncodedEvent>()
        val observability =
            ObservabilityFactory.create(
                sinks = listOf(CapturingSink(seen)),
            )

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "direct sink instance",
            )
        }

        assertEquals(1, seen.size)
        assertEquals(TestEvent.TEST.resolvedName(), seen.single().metadata["event"])
    }

    @Test
    fun `factory resolves externally registered provider`() {
        val seen = mutableListOf<EncodedEvent>()
        val customRegistry =
            SinkRegistry.default().withProvider(
                SinkProvider { config ->
                    if (config is ThirdPartySinkConfig) {
                        CapturingSink(seen)
                    } else {
                        null
                    }
                },
            )

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(ThirdPartySinkConfig("partner-a")),
                    sinkRegistry = customRegistry,
                ),
            )

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "provider sink",
            )
        }

        assertEquals(1, seen.size)
        assertEquals("INFO", seen.single().metadata["level"])
    }

    private class CapturedRequest(
        val method: String,
        val path: String,
        val contentType: String?,
        val body: ByteArray,
    )

    private data class ThirdPartySinkConfig(
        val target: String,
    ) : SinkConfig

    private class CapturingSink(
        private val seen: MutableList<EncodedEvent>,
    ) : ObservabilitySink {
        override fun handle(event: EncodedEvent) {
            seen += event.copy(metadata = event.metadata.toMutableMap())
        }
    }
}
