package io.github.aeshen.observability.codec.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.event
import io.github.aeshen.observability.key.BooleanKey
import io.github.aeshen.observability.key.DoubleKey
import io.github.aeshen.observability.key.LongKey
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class JsonLineCodecTest {
    private val objectMapper = ObjectMapper()

    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("test.event"),
    }

    @Test
    fun `codec supports null payload`() {
        val event =
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.INFO,
                payload = null,
                context = ObservabilityContext.empty(),
            )

        val encoded = JsonLineCodec().encode(event).encoded.toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"schemaVersion\":\"1\""))
        assertTrue(encoded.contains("\"eventId\":"))
        assertTrue(encoded.contains("\"correlationId\":null"))
        assertTrue(encoded.contains("\"payloadPresent\":false"))
        assertTrue(encoded.contains("\"payloadBase64\":\"\""))
        assertTrue(encoded.endsWith("\n"))
    }

    @Test
    fun `codec distinguishes empty payload from null payload`() {
        val encoded =
            JsonLineCodec()
                .encode(
                    ObservabilityEvent(
                        name = TestEvent.TEST,
                        level = EventLevel.INFO,
                        payload = byteArrayOf(),
                        context = ObservabilityContext.empty(),
                    ),
                ).encoded
                .toString(Charsets.UTF_8)

        assertTrue(encoded.contains("\"payloadPresent\":true"))
        assertTrue(encoded.contains("\"payloadBase64\":\"\""))
    }

    @Test
    fun `codec escapes context keys and values`() {
        val context =
            ObservabilityContext
                .builder()
                .put(StringKey.PATH, "line1\n\"line2\"")
                .build()
        val event =
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.INFO,
                context = context,
            )

        val encoded = JsonLineCodec().encode(event).encoded.toString(Charsets.UTF_8)
        assertTrue(encoded.contains("path"))
        assertTrue(encoded.contains("line1\\n\\\"line2\\\""))
    }

    @Test
    fun `codec includes core event fields`() {
        val encoded =
            JsonLineCodec()
                .encode(
                    event(TestEvent.TEST) {
                        level(EventLevel.WARN)
                        message("request failed")
                    },
                ).encoded
                .toString(Charsets.UTF_8)

        assertTrue(encoded.contains("\"name\":\"test.event\""))
        assertTrue(encoded.contains("\"level\":\"WARN\""))
        assertTrue(encoded.contains("\"timestamp\":"))
        assertTrue(encoded.contains("\"message\":\"request failed\""))
    }

    @Test
    fun `codec keeps numeric and boolean context values as json primitives`() {
        val context =
            ObservabilityContext
                .builder()
                .put(LongKey.STATUS_CODE, 200L)
                .put(DoubleKey.BYTES, 12.5)
                .put(BooleanKey.SUCCESS, true)
                .put(StringKey.REQUEST_ID, "req-1")
                .build()

        val encoded =
            JsonLineCodec()
                .encode(
                    ObservabilityEvent(
                        name = TestEvent.TEST,
                        level = EventLevel.INFO,
                        context = context,
                    ),
                ).encoded
                .toString(Charsets.UTF_8)

        assertTrue(encoded.contains("\"status_code\":200"))
        assertTrue(encoded.contains("\"bytes\":12.5"))
        assertTrue(encoded.contains("\"success\":true"))
        assertTrue(encoded.contains("\"id\":\"req-1\""))
        assertTrue(encoded.contains("\"correlationId\":\"req-1\""))
    }

    @Test
    fun `codec output conforms to event envelope json schema`() {
        val event =
            event(TestEvent.TEST) {
                level(EventLevel.ERROR)
                message("request failed")
                context(StringKey.REQUEST_ID, "req-1")
                context(LongKey.STATUS_CODE, 500L)
                error(IllegalStateException("boom"))
            }

        val encoded = JsonLineCodec().encode(event).encoded.toString(Charsets.UTF_8)
        val eventNode = objectMapper.readTree(encoded)
        val schemaNode =
            objectMapper.readTree(
                Files.readString(
                    Path.of("docs", "schema", "event-envelope-v1.schema.json"),
                    StandardCharsets.UTF_8,
                ),
            )
        val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val schema = schemaFactory.getSchema(schemaNode)
        val errors = schema.validate(eventNode)

        assertTrue(errors.isEmpty(), "Schema violations: ${errors.joinToString { it.message }}")
    }
}
