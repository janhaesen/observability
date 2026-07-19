package io.github.aeshen.observability.spring

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.Observability
import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.test.context.junit4.SpringRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

enum class TestEvents(override val eventName: String? = null) : EventName {
    TEST_EVENT("test.event"),
}

@RunWith(SpringRunner::class)
@SpringBootTest(
    classes = [ObservabilityIntegrationTest.TestApp::class],
    properties = [
        "observability.sinks[0].type=console",
        "observability.sinks[1].type=slf4j",
        "observability.sinks[1].logger=io.github.aeshen.observability.spring.ObservabilityIntegrationTest",
        "observability.fail-on-sink-error=true",
        "observability.profile=standard",
        "observability.async.enabled=true",
        "observability.async.capacity=10",
    ],
)
class ObservabilityIntegrationTest {
    @Autowired
    private lateinit var observability: Observability

    @Autowired
    private lateinit var diagnostics: ObservabilityDiagnostics

    @Autowired
    @Qualifier("observabilityHealthIndicator")
    private lateinit var healthIndicator: HealthIndicator

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Test
    fun testAutoConfiguration() {
        assertNotNull(observability)
        assertNotNull(diagnostics)
        assertTrue(diagnostics is InMemoryOperationalDiagnostics)

        observability.info(TestEvents.TEST_EVENT, "Integration test message")

        // Verify actuator health
        val health = healthIndicator.health()
        assertEquals(Status.UP, health.status)
        assertEquals("READY", health.details["status"])
        assertEquals(10, health.details["asyncQueueCapacity"])

        // Verify micrometer metrics binding
        val counter = meterRegistry.find("observability.async.drops").functionCounter()
        assertNotNull(counter)
    }

    @SpringBootApplication
    open class TestApp {
        @Bean
        open fun meterRegistry(): MeterRegistry {
            return SimpleMeterRegistry()
        }
    }
}
