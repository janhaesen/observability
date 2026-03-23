package io.github.aeshen.observability.sink.testing

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AdvancedConformanceTest {
    private enum class TestEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("advanced.conformance"),
    }

    @Test
    fun `pipeline failOnSinkError true propagates sink handle failures`() {
        val obs =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(FailingSink())),
                    sinkRegistry = directSinkRegistry(),
                    failOnSinkError = true,
                ),
            )

        assertFailsWith<IllegalStateException> {
            obs.use {
                it.info(
                    name = TestEvent.TEST,
                    message = "must fail",
                )
            }
        }
    }

    @Test
    fun `pipeline failOnSinkError false swallows sink handle failures`() {
        val obs =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(DirectSinkConfig(FailingSink())),
                    sinkRegistry = directSinkRegistry(),
                    failOnSinkError = false,
                ),
            )

        obs.use {
            it.info(
                name = TestEvent.TEST,
                message = "must be swallowed",
            )
        }
    }

    private class FailingSink : ObservabilitySink {
        override fun handle(event: EncodedEvent) {
            error("sink failure")
        }
    }

    private data class DirectSinkConfig(
        val sink: ObservabilitySink,
    ) : SinkConfig

    private fun directSinkRegistry(): SinkRegistry =
        SinkRegistry
            .builder()
            .register<DirectSinkConfig> { config -> config.sink }
            .build()
}
