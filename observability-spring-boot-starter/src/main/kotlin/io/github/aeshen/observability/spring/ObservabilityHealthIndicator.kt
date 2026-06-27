package io.github.aeshen.observability.spring

import io.github.aeshen.observability.diagnostics.HealthStatus
import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class ObservabilityHealthIndicator(
    private val diagnostics: ObservabilityDiagnostics,
) : HealthIndicator {
    override fun health(): Health {
        if (diagnostics !is InMemoryOperationalDiagnostics) {
            return Health.up()
                .withDetail("message", "Custom diagnostics configured, in-memory metrics not available")
                .build()
        }
        val healthSnapshot = diagnostics.healthSnapshot()
        val metricsSnapshot = diagnostics.metricsSnapshot()

        val builder =
            when (healthSnapshot.status) {
                HealthStatus.READY -> Health.up()
                HealthStatus.DEGRADED -> Health.status("DEGRADED")
                HealthStatus.UNHEALTHY -> Health.down()
            }

        return builder
            .withDetail("status", healthSnapshot.status.name)
            .withDetail("asyncWorkerHealthy", healthSnapshot.asyncWorkerHealthy)
            .withDetail("asyncWorkerMessage", healthSnapshot.asyncWorkerMessage ?: "OK")
            .withDetail("asyncQueueDepth", metricsSnapshot.asyncQueueDepth)
            .withDetail("asyncQueueCapacity", metricsSnapshot.asyncQueueCapacity)
            .withDetail("asyncQueueDepthMax", metricsSnapshot.asyncQueueDepthMax)
            .withDetail("sinkHandleErrors", metricsSnapshot.sinkHandleErrors)
            .withDetail("sinkCloseErrors", metricsSnapshot.sinkCloseErrors)
            .withDetail("asyncDrops", metricsSnapshot.asyncDrops)
            .withDetail("retryExhaustions", metricsSnapshot.retryExhaustions)
            .build()
    }
}
