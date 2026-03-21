package io.github.aeshen.observability.sink.registry

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SinkRegistryTest {
    private data class CustomSinkConfig(
        val endpoint: String,
    ) : SinkConfig

    private class CustomSink : ObservabilitySink {
        override fun handle(event: EncodedEvent) = Unit
    }

    @Test
    fun `empty registry can be filled with custom provider`() {
        val registry =
            SinkRegistry
                .empty()
                .withProvider { config ->
                    if (config is CustomSinkConfig) {
                        CustomSink()
                    } else {
                        null
                    }
                }

        val resolved = registry.resolve(CustomSinkConfig("https://partner.example/logs"))
        assertNotNull(resolved)
    }

    @Test
    fun `empty registry starts with no resolvers`() {
        val registry = SinkRegistry.empty()

        assertFailsWith<IllegalStateException> {
            registry.resolve(CustomSinkConfig("unused"))
        }
    }

    @Test
    fun `default registry still resolves built in sinks`() {
        val resolved = SinkRegistry.default().resolve(Console)
        assertNotNull(resolved)
    }
}
