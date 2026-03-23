package io.github.aeshen.observability

import com.sun.net.httpserver.HttpServer
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.config.sink.OpenTelemetry
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.processor.builtin.SensitiveFieldProcessor
import io.github.aeshen.observability.processor.builtin.SensitiveFieldRule
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `factory supports runtime sink instance via registry adapter`() {
        val seen = mutableListOf<EncodedEvent>()
        val registry = directSinkRegistry()
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(CapturingSink(seen))),
                    sinkRegistry = registry,
                ),
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
    fun `factory accepts custom codec`() {
        val seen = mutableListOf<EncodedEvent>()
        val customCodec =
            ObservabilityCodec { event ->
                EncodedEvent(
                    original = event,
                    encoded = "custom".toByteArray(Charsets.UTF_8),
                )
            }
        val registry = directSinkRegistry()
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(CapturingSink(seen))),
                    sinkRegistry = registry,
                    codec = customCodec,
                ),
            )

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "codec override",
            )
        }

        assertEquals("custom", seen.single().encoded.toString(Charsets.UTF_8))
    }

    @Test
    fun `factory resolves externally registered provider`() {
        val seen = mutableListOf<EncodedEvent>()
        val customRegistry =
            SinkRegistry
                .defaultBuilder()
                .register<ThirdPartySinkConfig> { CapturingSink(seen) }
                .build()

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

    @Test
    fun `factory resolves provider registered via builder api`() {
        val seen = mutableListOf<EncodedEvent>()
        val customRegistry =
            SinkRegistry
                .builder()
                .register<ThirdPartySinkConfig> { CapturingSink(seen) }
                .build()

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(ThirdPartySinkConfig("partner-b")),
                    sinkRegistry = customRegistry,
                ),
            )

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "builder sink",
            )
        }

        assertEquals(1, seen.size)
        assertEquals("INFO", seen.single().metadata["level"])
    }

    @Test
    fun `factory applies configured context providers`() {
        val seen = mutableListOf<EncodedEvent>()
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(ThirdPartySinkConfig("partner-c")),
                    sinkRegistry =
                    SinkRegistry
                        .builder()
                        .register<ThirdPartySinkConfig> { CapturingSink(seen) }
                        .build(),
                    contextProviders =
                    listOf(
                        ContextProvider {
                            ObservabilityContext
                                .builder()
                                .put(StringKey.USER_AGENT, "provider-agent")
                                .build()
                        },
                    ),
                ),
            )

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "provider context",
            )
        }

        val original = seen.single().original
        assertEquals("provider-agent", original.context.get(StringKey.USER_AGENT))
    }

    @Test
    fun `factory config accepts custom processors`() {
        val seen = mutableListOf<EncodedEvent>()
        val passwordKey =
            object : io.github.aeshen.observability.key.TypedKey<String> {
                override val keyName: String = "password"
            }
        val processor =
            SensitiveFieldProcessor(
                rules = listOf(SensitiveFieldRule.remove("context.password")),
            )
        val registry =
            SinkRegistry
                .builder()
                .register<ThirdPartySinkConfig> { CapturingSink(seen) }
                .build()

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(ThirdPartySinkConfig("partner-processor")),
                    sinkRegistry = registry,
                    processors = listOf(processor),
                ),
            )

        val context =
            ObservabilityContext
                .builder()
                .put(passwordKey, "secret")
                .build()

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "processor via config",
                context = context,
            )
        }

        val encoded = seen.single().encoded.toString(Charsets.UTF_8)
        assertTrue(encoded.contains("processor via config"))
        assertTrue(encoded.contains("\"context\":{}"))
        assertTrue(encoded.contains("\"payloadPresent\":false"))
        assertTrue(encoded.contains("\"payloadBase64\":\"\""))
        assertTrue(encoded.contains("\"message\":\"processor via config\""))
        assertTrue(!encoded.contains("secret"))
    }

    @Test
    fun `audit durable profile enforces strict sink failures`() {
        val failing =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) = error("always fails")
            }
        val registry = directSinkRegistry()

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(failing)),
                    sinkRegistry = registry,
                    failOnSinkError = false,
                    profile = ObservabilityFactory.Profile.AUDIT_DURABLE,
                ),
            )

        assertFailsWith<IllegalStateException> {
            observability.use {
                it.info(
                    name = TestEvent.TEST,
                    message = "must fail",
                )
            }
        }
    }

    @Test
    fun `audit durable profile retries transient failures`() {
        val attempts = AtomicInteger(0)
        val flaky =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) {
                    if (attempts.incrementAndGet() < 3) {
                        error("transient")
                    }
                }
            }
        val registry = directSinkRegistry()

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(flaky)),
                    sinkRegistry = registry,
                    profile = ObservabilityFactory.Profile.AUDIT_DURABLE,
                ),
            )

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "eventually succeeds",
            )
        }

        assertEquals(3, attempts.get())
    }

    @Test
    fun `diagnostics are wired through factory config`() {
        val diagnosticEvents = mutableListOf<String>()
        val diagnostics =
            object : io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics {
                override fun onBatchFlush(
                    batchSize: Int,
                    elapsedMillis: Long,
                    success: Boolean,
                    error: Exception?,
                ) {
                    diagnosticEvents += "flush:$batchSize:$success"
                }

                override fun onRetryExhaustion(
                    event: EncodedEvent,
                    attempts: Int,
                    lastError: Exception,
                ) {
                    diagnosticEvents += "retry_fail:$attempts"
                }
            }

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(ThirdPartySinkConfig("test")),
                    sinkRegistry =
                    SinkRegistry
                        .builder()
                        .register<ThirdPartySinkConfig> {
                            CapturingSink(mutableListOf())
                        }
                        .build(),
                    profile = ObservabilityFactory.Profile.AUDIT_DURABLE,
                    diagnostics = diagnostics,
                ),
            )

        observability.use {
            it.info(
                name = TestEvent.TEST,
                message = "with diagnostics",
            )
        }

        assertTrue(
            diagnosticEvents.any { it.startsWith("flush") },
            "Batch flush should be reported by diagnostics",
        )
    }

    @Test
    fun `audit profile with diagnostics tracks retry exhaustion`() {
        val diagnosticEvents = mutableListOf<String>()
        val diagnostics =
            object : io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics {
                override fun onRetryExhaustion(
                    event: EncodedEvent,
                    attempts: Int,
                    lastError: Exception,
                ) {
                    diagnosticEvents += "exhausted:$attempts"
                }
            }

        val failing =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) = error("always fail")
            }
        val registry = directSinkRegistry()

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(failing)),
                    sinkRegistry = registry,
                    profile = ObservabilityFactory.Profile.AUDIT_DURABLE,
                    diagnostics = diagnostics,
                ),
            )

        assertFailsWith<IllegalStateException> {
            observability.use {
                it.info(
                    name = TestEvent.TEST,
                    message = "will exhaust retries",
                )
            }
        }

        assertTrue(
            diagnosticEvents.any { it.contains("exhausted") },
            "Retry exhaustion should be reported",
        )
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

    private data class DirectSinkConfig(
        val sink: ObservabilitySink,
    ) : SinkConfig

    private fun directSinkRegistry(): SinkRegistry =
        SinkRegistry
            .builder()
            .register<DirectSinkConfig> { config -> config.sink }
            .build()

    private class CapturingSink(
        private val seen: MutableList<EncodedEvent>,
    ) : ObservabilitySink {
        override fun handle(event: EncodedEvent) {
            seen += event.copy(metadata = event.metadata.toMutableMap())
        }
    }
}
