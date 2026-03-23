package io.github.aeshen.observability.processor.builtin

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.enricher.MetadataEnricher
import io.github.aeshen.observability.event
import io.github.aeshen.observability.key.TypedKey
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitiveFieldProcessorTest {
    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        LOGIN("auth.login"),
    }

    private val passwordKey = textKey("password")
    private val emailKey = textKey("email")
    private val requestIdKey = textKey("requestId")
    private val apiTokenKey = textKey("apiToken")
    private val accessTokenKey = textKey("accessToken")

    @Test
    fun `processor masks and removes configured fields for default jsonl`() {
        val sink = CapturingSink()
        val rules =
            listOf(
                SensitiveFieldRule.allow("context.requestId"),
                SensitiveFieldRule.remove("context.password"),
                SensitiveFieldRule.mask("context.email", "[EMAIL]"),
                SensitiveFieldRule.mask("metadata.apiToken", "[TOKEN]"),
                SensitiveFieldRule.mask("message", "[MESSAGE]"),
                SensitiveFieldRule.remove("payload"),
            )
        val processor =
            SensitiveFieldProcessor(
                rules = rules,
            )
        val enrichers =
            listOf(
                MetadataEnricher { encoded ->
                    encoded.metadata["apiToken"] = "meta-secret"
                    encoded.metadata["requestId"] = "meta-req"
                },
            )
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(sink)),
                    sinkRegistry = directSinkRegistry(),
                    processors = listOf(processor),
                    metadataEnrichers = enrichers,
                ),
            )

        observability.use {
            it.emit(
                event(TestEvent.LOGIN) {
                    level(EventLevel.INFO)
                    message("customer email is jane@example.com")
                    payload("raw-secret-payload".toByteArray(Charsets.UTF_8))
                    context(passwordKey, "hunter2")
                    context(emailKey, "jane@example.com")
                    context(requestIdKey, "req-123")
                },
            )
        }

        val delivered = sink.events.single()
        val encodedText = delivered.encoded.toString(Charsets.UTF_8)

        assertNull(delivered.original.context.get(passwordKey))
        assertEquals("[EMAIL]", delivered.original.context.get(emailKey))
        assertEquals("req-123", delivered.original.context.get(requestIdKey))
        assertEquals("[MESSAGE]", delivered.original.message)
        assertNull(delivered.original.payload)

        assertEquals("[TOKEN]", delivered.metadata["apiToken"])
        assertEquals("meta-req", delivered.metadata["requestId"])

        assertTrue(encodedText.contains("\"message\":\"[MESSAGE]\""))
        assertTrue(encodedText.contains("\"email\":\"[EMAIL]\""))
        assertTrue(encodedText.contains("\"payloadPresent\":false"))
        assertFalse(encodedText.contains("hunter2"))
        assertFalse(encodedText.contains("jane@example.com"))
        assertFalse(encodedText.contains("raw-secret-payload"))
    }

    @Test
    fun `explicit allow rules win over preset secret masks`() {
        val sink = CapturingSink()
        val processor =
            SensitiveFieldProcessor(
                rules = listOf(SensitiveFieldRule.allow("context.apiToken")),
                presets = SensitiveFieldPresets.commonSecrets(mask = "[MASKED]"),
            )
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(sink)),
                    sinkRegistry = directSinkRegistry(),
                    processors = listOf(processor),
                ),
            )

        val context =
            ObservabilityContext
                .builder()
                .put(apiTokenKey, "keep-visible")
                .put(accessTokenKey, "hide-me")
                .build()

        observability.use {
            it.info(
                name = TestEvent.LOGIN,
                message = "preset coverage",
                context = context,
            )
        }

        val delivered = sink.events.single()
        val encodedText = delivered.encoded.toString(Charsets.UTF_8)

        assertEquals("keep-visible", delivered.original.context.get(apiTokenKey))
        assertEquals("[MASKED]", delivered.original.context.get(accessTokenKey))
        assertTrue(encodedText.contains("keep-visible"))
        assertTrue(encodedText.contains("[MASKED]"))
        assertFalse(encodedText.contains("hide-me"))
    }

    @Test
    fun `processor sanitizes json based custom codecs where applicable`() {
        val sink = CapturingSink()
        val encodedJson =
            """
            {"message":"codec-secret","context":{"password":"json-password"},"metadata":{"sessionToken":"json-token"},"payload":"json-payload"}
            """
                .trimIndent()
        val customCodec =
            ObservabilityCodec { event ->
                EncodedEvent(
                    original = event,
                    encoded = encodedJson.toByteArray(Charsets.UTF_8),
                )
            }
        val rules =
            listOf(
                SensitiveFieldRule.mask("message", "[CODEC]"),
                SensitiveFieldRule.remove("context.password"),
                SensitiveFieldRule.mask("metadata.sessionToken", "[SESSION]"),
                SensitiveFieldRule.remove("payload"),
            )
        val processor =
            SensitiveFieldProcessor(
                rules = rules,
            )

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(sink)),
                    sinkRegistry = directSinkRegistry(),
                    processors = listOf(processor),
                    codec = customCodec,
                ),
            )

        val eventContext =
            ObservabilityContext
                .builder()
                .put(passwordKey, "event-password")
                .build()

        observability.use {
            it.info(
                name = TestEvent.LOGIN,
                message = "event-message",
                context = eventContext,
            )
        }

        val encodedText = sink.events.single().encoded.toString(Charsets.UTF_8)
        assertTrue(encodedText.contains("\"message\":\"[CODEC]\""))
        assertTrue(encodedText.contains("\"sessionToken\":\"[SESSION]\""))
        assertFalse(encodedText.contains("json-password"))
        assertFalse(encodedText.contains("json-token"))
        assertFalse(encodedText.contains("json-payload"))
    }

    @Test
    fun `factory runs custom processors before encryption`() {
        val sink = CapturingSink()
        val checks = mutableListOf<String>()
        val processor =
            object : ObservabilityProcessor {
                override fun process(event: EncodedEvent): EncodedEvent {
                    val plaintext = event.encoded.toString(Charsets.UTF_8)
                    val metadata = event.metadata.toMutableMap()
                    checks += plaintext
                    check(plaintext.contains("processor-order-secret")) {
                        "Custom processors must see plaintext before encryption."
                    }
                    metadata["processorChecked"] = true
                    return event.copy(metadata = metadata)
                }
            }
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(sink)),
                    sinkRegistry = directSinkRegistry(),
                    processors = listOf(processor),
                    encryption = ObservabilityFactory.Config.aesGcmFromRawKeyBytes(ByteArray(32) { 7 }),
                ),
            )

        observability.use {
            it.info(
                name = TestEvent.LOGIN,
                message = "processor-order-secret",
            )
        }

        val delivered = sink.events.single()
        val encodedText = delivered.encoded.toString(Charsets.UTF_8)

        assertEquals(1, checks.size)
        assertEquals(true, delivered.metadata["processorChecked"])
        assertTrue(encodedText.contains("\"ciphertext\":\""))
        assertFalse(encodedText.contains("processor-order-secret"))
    }

    private fun textKey(name: String): TypedKey<String> =
        object : TypedKey<String> {
            override val keyName: String = name
        }

    private data class DirectSinkConfig(
        val sink: ObservabilitySink,
    ) : SinkConfig

    private fun directSinkRegistry(): SinkRegistry =
        SinkRegistry
            .builder()
            .register<DirectSinkConfig> { config -> config.sink }
            .build()

    private class CapturingSink : ObservabilitySink {
        val events = mutableListOf<EncodedEvent>()

        override fun handle(event: EncodedEvent) {
            events +=
                event.copy(
                    encoded = event.encoded.copyOf(),
                    metadata = LinkedHashMap(event.metadata),
                )
        }
    }
}
