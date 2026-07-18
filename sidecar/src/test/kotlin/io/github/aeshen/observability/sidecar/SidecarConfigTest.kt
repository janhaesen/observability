package io.github.aeshen.observability.sidecar

import io.github.aeshen.observability.ObservabilityFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SidecarConfigTest {
    @Test
    fun `unauthenticated sidecar accepts loopback binding`() {
        val config = SidecarConfig.fromEnvironment(mapOf("SIDECAR_HOST" to "127.0.0.1"))

        assertEquals("127.0.0.1", config.host)
        assertNull(config.bearerToken)
    }

    @Test
    fun `unauthenticated sidecar rejects public binding`() {
        assertFailsWith<IllegalArgumentException> {
            SidecarConfig.fromEnvironment(mapOf("SIDECAR_HOST" to "0.0.0.0"))
        }
    }

    @Test
    fun `audit durable requires persistent storage`() {
        assertFailsWith<IllegalArgumentException> {
            SidecarConfig.fromEnvironment(
                mapOf(
                    "SIDECAR_PROFILE" to ObservabilityFactory.Profile.AUDIT_DURABLE.name,
                ),
            )
        }
    }
}
