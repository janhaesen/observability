package io.github.aeshen.observability.sink.registry

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.config.sink.Http
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
    fun `builder register creates typed custom mapping`() {
        val registry =
            SinkRegistry
                .builder()
                .register<CustomSinkConfig> { CustomSink() }
                .build()

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

        val httpResolved =
            SinkRegistry.default().resolve(Http(endpoint = "https://example.com/ingest"))
        assertNotNull(httpResolved)
    }

    @Test
    fun `toBuilder allows incremental extension`() {
        val registry =
            SinkRegistry
                .default()
                .toBuilder()
                .register<CustomSinkConfig> { CustomSink() }
                .build()

        assertNotNull(registry.resolve(Console))
        assertNotNull(registry.resolve(CustomSinkConfig("https://partner.example/logs")))
    }
}
