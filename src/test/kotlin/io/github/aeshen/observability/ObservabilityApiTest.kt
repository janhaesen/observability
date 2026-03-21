package io.github.aeshen.observability

import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ObservabilityApiTest {
    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        FALLBACK,
        REQUEST_DONE("request.done"),
    }

    private class CapturingObservability : Observability {
        val emitted = mutableListOf<ObservabilityEvent>()

        override fun emit(event: ObservabilityEvent) {
            emitted += event
        }

        override fun close() = Unit
    }

    @Test
    fun `info helper emits info level event`() {
        val obs = CapturingObservability()
        val context = ObservabilityContext.builder().put(StringKey.REQUEST_ID, "req-1").build()

        obs.info(TestEvent.REQUEST_DONE, "done", context)

        val event = obs.emitted.single()
        assertEquals(EventLevel.INFO, event.level)
        assertEquals("request.done", event.name.resolvedName())
        assertEquals("done", event.message)
        assertEquals("req-1", event.context.get(StringKey.REQUEST_ID))
    }

    @Test
    fun `warn helper forwards throwable`() {
        val obs = CapturingObservability()
        val error = IllegalStateException("boom")

        obs.warn(TestEvent.FALLBACK, "warn", throwable = error)

        val event = obs.emitted.single()
        assertEquals(EventLevel.WARN, event.level)
        assertEquals("FALLBACK", event.name.resolvedName())
        assertEquals(error, event.error)
    }

    @Test
    fun `event dsl builds event with context and payload`() {
        val payload = "hello".toByteArray()
        val event =
            event(TestEvent.REQUEST_DONE) {
                level(EventLevel.DEBUG)
                message("built")
                payload(payload)
                context(StringKey.PATH, "/orders")
            }

        assertEquals(EventLevel.DEBUG, event.level)
        assertEquals("built", event.message)
        assertEquals(payload.toList(), event.payload?.toList())
        assertEquals("/orders", event.context.get(StringKey.PATH))
        assertNotNull(event.timestamp)
    }
}
