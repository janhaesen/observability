package io.github.aeshen.observability.config.sink

import kotlin.test.Test
import kotlin.test.assertFailsWith

class OpenTelemetryConfigTest {
    @Test
    fun `rejects non-positive batch settings`() {
        assertFailsWith<IllegalArgumentException> {
            OpenTelemetry(scheduleDelayMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            OpenTelemetry(exporterTimeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            OpenTelemetry(maxQueueSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            OpenTelemetry(maxExportBatchSize = 0)
        }
    }

    @Test
    fun `rejects export batch larger than queue`() {
        assertFailsWith<IllegalArgumentException> {
            OpenTelemetry(maxQueueSize = 10, maxExportBatchSize = 11)
        }
    }
}

