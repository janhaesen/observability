package io.github.aeshen.observability.example

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry
import io.github.aeshen.observability.sink.testing.ObservabilitySinkConformanceSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class PartnerSinkConformanceTest : ObservabilitySinkConformanceSuite() {
    private lateinit var sink: PartnerHttpSink

    override fun createSubjectSink(): ObservabilitySink {
        sink = PartnerHttpSink(endpoint = "https://partner.example/logs")
        return sink
    }

    override fun observedEvents(): List<EncodedEvent> = sink.receivedEvents()

    @Test
    fun `sink forwards handled event bytes and metadata`() {
        assertForwardsHandledEventBytesAndMetadata()
    }

    @Test
    fun `close can be called repeatedly`() {
        assertCloseCanBeCalledRepeatedly()
    }

    @Test
    fun `sink retains endpoint configured by provider config`() {
        sink = PartnerHttpSink(endpoint = "https://partner.example/logs")
        assertEquals("https://partner.example/logs", sink.endpoint())
    }

    @Test
    fun `provider resolves custom config through sink registry`() {
        val registry = SinkRegistry.default().withProvider(PartnerSinkProvider)
        val obs =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(PartnerSinkConfig("https://partner.example/logs")),
                    sinkRegistry = registry,
                ),
            )

        obs.use {
            it.info(
                name = ProviderEvent.TEST,
                message = "provider works",
            )
        }
    }

    private enum class ProviderEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("provider.integration.test"),
    }
}
