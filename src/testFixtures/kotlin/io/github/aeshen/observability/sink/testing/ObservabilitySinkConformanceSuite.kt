package io.github.aeshen.observability.sink.testing

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reusable contract tests for third-party sink implementations.
 */
abstract class ObservabilitySinkConformanceSuite {
    protected abstract fun createSubjectSink(): ObservabilitySink

    /**
     * Implementations should return the events observed by the subject sink in receive order.
     */
    protected abstract fun observedEvents(): List<EncodedEvent>

    protected fun assertForwardsHandledEventBytesAndMetadata() {
        createSubjectSink().use { sink ->
            sink.handle(sampleEvent("conformance-one", mapOf("source" to "suite")))
        }

        val observed = observedEvents()
        assertEquals(1, observed.size)
        assertEquals("conformance-one", observed.single().encoded.toString(Charsets.UTF_8))
        assertEquals("suite", observed.single().metadata["source"])
    }

    protected fun assertCloseCanBeCalledRepeatedly() {
        val sink = createSubjectSink()
        sink.close()
        sink.close()
        assertTrue(true)
    }

    protected fun sampleEvent(
        payload: String,
        metadata: Map<String, Any?> = emptyMap(),
    ): EncodedEvent =
        EncodedEvent(
            original =
                ObservabilityEvent(
                    name = ConformanceEvent.TEST,
                    level = EventLevel.INFO,
                    context = ObservabilityContext.empty(),
                ),
            encoded = payload.toByteArray(Charsets.UTF_8),
            metadata = metadata.toMutableMap(),
        )

    private enum class ConformanceEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("sink.conformance.test"),
    }
}

