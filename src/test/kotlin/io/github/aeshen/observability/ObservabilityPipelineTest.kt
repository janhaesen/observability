package io.github.aeshen.observability

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObservabilityPipelineTest {
    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("test.event"),
    }

    private class StaticCodec(
        private val bytes: ByteArray,
    ) : ObservabilityCodec {
        override fun encode(event: ObservabilityEvent): EncodedEvent =
            EncodedEvent(
                original = event,
                encoded = bytes.copyOf(),
            )
    }

    private class RecordingSink : ObservabilitySink {
        val events = mutableListOf<EncodedEvent>()
        var closed = false

        override fun handle(event: EncodedEvent) {
            events += event
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `pipeline applies processors before sinking`() {
        val sink = RecordingSink()
        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                processors =
                    listOf(
                        object : ObservabilityProcessor {
                            override fun process(event: EncodedEvent): EncodedEvent =
                                event.copy(
                                    encoded =
                                        event.encoded +
                                            ":processed"
                                                .toByteArray(),
                                )
                        },
                    ),
                sinks = listOf(sink),
            )

        pipeline.emit(
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.INFO,
                context = ObservabilityContext.empty(),
            ),
        )

        assertContentEquals("raw:processed".toByteArray(), sink.events.single().encoded)
    }

    @Test
    fun `pipeline includes standard metadata`() {
        val sink = RecordingSink()
        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("abc".toByteArray()),
                processors = emptyList(),
                sinks = listOf(sink),
            )

        pipeline.emit(
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.ERROR,
                context = ObservabilityContext.empty(),
            ),
        )

        val metadata = sink.events.single().metadata
        assertEquals("test.event", metadata["event"])
        assertEquals("ERROR", metadata["level"])
        assertEquals(3, metadata["size"])
    }

    @Test
    fun `pipeline swallows sink errors in non strict mode`() {
        val healthy = RecordingSink()
        val failing =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) = error("sink failed")
            }
        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                processors = emptyList(),
                sinks = listOf(failing, healthy),
                failOnSinkError = false,
            )

        pipeline.emit(
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.INFO,
                context = ObservabilityContext.empty(),
            ),
        )

        assertEquals(1, healthy.events.size)
    }

    @Test
    fun `pipeline throws sink errors in strict mode`() {
        val failing =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) = error("sink failed")
            }
        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                processors = emptyList(),
                sinks = listOf(failing),
                failOnSinkError = true,
            )

        assertFailsWith<IllegalStateException> {
            pipeline.emit(
                ObservabilityEvent(
                    name = TestEvent.TEST,
                    level = EventLevel.INFO,
                    context = ObservabilityContext.empty(),
                ),
            )
        }
    }

    @Test
    fun `pipeline does not swallow fatal errors`() {
        val fatal =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) = throw OutOfMemoryError("fatal")
            }

        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                processors = emptyList(),
                sinks = listOf(fatal),
                failOnSinkError = false,
            )

        assertFailsWith<OutOfMemoryError> {
            pipeline.emit(
                ObservabilityEvent(
                    name = TestEvent.TEST,
                    level = EventLevel.INFO,
                    context = ObservabilityContext.empty(),
                ),
            )
        }
    }

    @Test
    fun `close in strict mode closes all sinks and rethrows first error`() {
        val sink1 =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) = Unit

                override fun close() = error("close1")
            }
        val sink2 = RecordingSink()
        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                processors = emptyList(),
                sinks = listOf(sink1, sink2),
                failOnSinkError = true,
            )

        val ex = assertFailsWith<IllegalStateException> { pipeline.close() }
        assertEquals("close1", ex.message)
        assertEquals(true, sink2.closed)
    }

    @Test
    fun `emit after close fails deterministically`() {
        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                processors = emptyList(),
                sinks = listOf(RecordingSink()),
            )

        pipeline.close()

        assertFailsWith<IllegalStateException> {
            pipeline.emit(
                ObservabilityEvent(
                    name = TestEvent.TEST,
                    level = EventLevel.INFO,
                    context = ObservabilityContext.empty(),
                ),
            )
        }
    }

    @Test
    fun `pipeline applies context providers to emitted event context`() {
        val sink = RecordingSink()
        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                contextProviders =
                    listOf(
                        ContextProvider {
                            ObservabilityContext
                                .builder()
                                .put(StringKey.USER_AGENT, "provider-agent")
                                .put(StringKey.REQUEST_ID, "provider-id")
                                .build()
                        },
                    ),
                processors = emptyList(),
                sinks = listOf(sink),
            )

        val context =
            ObservabilityContext
                .builder()
                .put(StringKey.REQUEST_ID, "event-id")
                .build()

        pipeline.emit(
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.INFO,
                context = context,
            ),
        )

        val original = sink.events.single().original
        assertEquals("provider-agent", original.context.get(StringKey.USER_AGENT))
        assertEquals("event-id", original.context.get(StringKey.REQUEST_ID))
    }

    @Test
    fun `pipeline reports sink diagnostics for handle and close errors`() {
        val handleErrors = mutableListOf<String>()
        val closeErrors = mutableListOf<String>()
        val diagnostics =
            object : ObservabilityDiagnostics {
                override fun onSinkHandleError(
                    sink: ObservabilitySink,
                    event: EncodedEvent,
                    error: Exception,
                ) {
                    handleErrors += error.message.orEmpty()
                }

                override fun onSinkCloseError(
                    sink: ObservabilitySink,
                    error: Exception,
                ) {
                    closeErrors += error.message.orEmpty()
                }
            }

        val failing =
            object : ObservabilitySink {
                override fun handle(event: EncodedEvent) = error("handle-failed")

                override fun close() = error("close-failed")
            }

        val pipeline =
            ObservabilityPipeline(
                codec = StaticCodec("raw".toByteArray()),
                processors = emptyList(),
                sinks = listOf(failing),
                failOnSinkError = false,
                diagnostics = diagnostics,
            )

        pipeline.emit(
            ObservabilityEvent(
                name = TestEvent.TEST,
                level = EventLevel.INFO,
                context = ObservabilityContext.empty(),
            ),
        )
        pipeline.close()

        assertEquals(listOf("handle-failed"), handleErrors)
        assertEquals(listOf("close-failed"), closeErrors)
    }
}
