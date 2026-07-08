package io.github.aeshen.observability.spring

import io.github.aeshen.observability.config.sink.Kafka
import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObservabilityAutoConfigurationTest {
    @Test
    fun defaultRegistryKeepsOptionalDependencyErrors() {
        val registry =
            ObservabilityAutoConfiguration().sinkRegistry(
                customProviders = emptyList(),
                properties = ObservabilityProperties(),
                diagnostics = InMemoryOperationalDiagnostics(),
            )

        val error =
            assertFailsWith<IllegalStateException> {
                registry.resolve(
                    Kafka(
                        bootstrapServers = "localhost:9092",
                        topic = "events",
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("Kafka sink requires optional runtime dependencies"))
    }
}
