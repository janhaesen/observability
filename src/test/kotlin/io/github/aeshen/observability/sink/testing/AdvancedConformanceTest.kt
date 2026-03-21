package io.github.aeshen.observability.sink.testing

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
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
                FailingSink(),
                failOnSinkError = true,
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
                FailingSink(),
                failOnSinkError = false,
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
}
