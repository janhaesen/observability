package io.github.aeshen.observability.codec.impl

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel
import kotlin.test.Test
import kotlin.test.assertTrue

class JsonLineCodecTest {
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
        assertTrue(encoded.contains("\"payloadBase64\": \"\""))
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
}
