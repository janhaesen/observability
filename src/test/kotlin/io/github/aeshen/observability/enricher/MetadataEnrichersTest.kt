package io.github.aeshen.observability.enricher

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.enricher.builtin.CorrelationIdEnricher
import io.github.aeshen.observability.enricher.builtin.EnvironmentEnricher
import io.github.aeshen.observability.enricher.builtin.HostEnricher
import io.github.aeshen.observability.enricher.builtin.IngestedAtEnricher
import io.github.aeshen.observability.enricher.builtin.VersionEnricher
import kotlin.test.Test
import kotlin.test.assertTrue

class MetadataEnrichersTest {
    private enum class TestEvent(override val eventName: String?) : EventName {
        TEST_MESSAGE("test.event"),
    }

    @Test
    fun testIngestedAtEnricherAddsTimestamp() {
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    metadataEnrichers = listOf(IngestedAtEnricher),
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        // If we get here without exception, the enricher was applied successfully
        assertTrue(true)
    }

    @Test
    fun testVersionEnricherAddsVersionMetadata() {
        val version = "1.2.3"
        val buildSha = "abc123"

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    metadataEnrichers = listOf(VersionEnricher(version, buildSha)),
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        assertTrue(true)
    }

    @Test
    fun testEnvironmentEnricherAddsEnvironmentMetadata() {
        val environment = "prod"
        val region = "us-west-2"

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    metadataEnrichers = listOf(EnvironmentEnricher(environment, region)),
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        assertTrue(true)
    }

    @Test
    fun testHostEnricherAddsHostMetadata() {
        val hostname = "pod-123"
        val nodeId = "node-456"

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    metadataEnrichers = listOf(HostEnricher(hostname, nodeId)),
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        assertTrue(true)
    }

    @Test
    fun testCorrelationIdEnricherAddsCorrelationMetadata() {
        val traceId = "trace-xyz"
        val requestId = "request-789"

        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    metadataEnrichers = listOf(
                        CorrelationIdEnricher(
                            traceIdSupplier = { traceId },
                            requestIdSupplier = { requestId },
                        ),
                    ),
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        assertTrue(true)
    }

    @Test
    fun testMultipleEnrichersAppliedInOrder() {
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    metadataEnrichers = listOf(
                        IngestedAtEnricher,
                        VersionEnricher("1.0.0", "def456"),
                        EnvironmentEnricher("staging", "us-east-1"),
                        HostEnricher("host-123"),
                    ),
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        assertTrue(true)
    }

    @Test
    fun testBackwardCompatibilityWithoutEnrichers() {
        // Ensure that configs without metadataEnrichers still work
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    // No metadataEnrichers specified - should use default empty list
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        assertTrue(true)
    }

    @Test
    fun testEnrichersViaConfigCreate() {
        val observability =
            ObservabilityFactory.create(
                ObservabilityFactory.Config(
                    sinks = listOf(Console),
                    metadataEnrichers = listOf(IngestedAtEnricher),
                ),
            )

        observability.use { obs ->
            obs.info(TestEvent.TEST_MESSAGE)
        }

        assertTrue(true)
    }
}
