package io.github.aeshen.observability.context.provider

import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.key.StringKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoroutineObservabilityContextTest {

    private val provider = CoroutineContextProvider()

    @AfterTest
    fun cleanup() {
        observabilityContextThreadLocal.remove()
    }

    @Test
    fun `provider returns empty context outside coroutine scope`() {
        val context = provider.provide()
        assertTrue(context.asMap().isEmpty())
    }

    @Test
    fun `withObservabilityContext makes context available to provider`() = runTest {
        val ctx = ObservabilityContext.builder()
            .put(StringKey.REQUEST_ID, "req-001")
            .build()

        withObservabilityContext(ctx) {
            val provided = provider.provide()
            assertEquals("req-001", provided.get(StringKey.REQUEST_ID))
        }
    }

    @Test
    fun `context is cleared after withObservabilityContext block exits`() = runTest {
        val ctx = ObservabilityContext.builder()
            .put(StringKey.REQUEST_ID, "req-002")
            .build()

        withObservabilityContext(ctx) { /* no-op */ }

        val provided = provider.provide()
        assertTrue(provided.asMap().isEmpty())
    }

    @Test
    fun `inner withObservabilityContext overrides outer context`() = runTest {
        val outer = ObservabilityContext.builder()
            .put(StringKey.USER_AGENT, "outer-agent")
            .build()

        val inner = ObservabilityContext.builder()
            .put(StringKey.USER_AGENT, "inner-agent")
            .build()

        withObservabilityContext(outer) {
            assertEquals("outer-agent", provider.provide().get(StringKey.USER_AGENT))

            withObservabilityContext(inner) {
                assertEquals("inner-agent", provider.provide().get(StringKey.USER_AGENT))
            }

            // outer context is restored
            assertEquals("outer-agent", provider.provide().get(StringKey.USER_AGENT))
        }
    }

    @Test
    fun `context propagates across coroutine context switches`() = runTest {
        val ctx = ObservabilityContext.builder()
            .put(StringKey.REQUEST_ID, "cross-thread")
            .build()

        withObservabilityContext(ctx) {
            withContext(Dispatchers.Default) {
                assertEquals("cross-thread", provider.provide().get(StringKey.REQUEST_ID))
            }
        }
    }

    @Test
    fun `coroutine context element key matches companion object`() {
        val ctx = ObservabilityContext.empty()
        val element = ObservabilityCoroutineContext(ctx)
        assertEquals(ObservabilityCoroutineContext.Key, element.key)
    }

    @Test
    fun `multiple keys are all available inside scope`() = runTest {
        val ctx = ObservabilityContext.builder()
            .put(StringKey.REQUEST_ID, "r-1")
            .put(StringKey.USER_AGENT, "browser/1.0")
            .put(StringKey.PATH, "/api/v1/orders")
            .build()

        withObservabilityContext(ctx) {
            val provided = provider.provide()
            assertEquals("r-1", provided.get(StringKey.REQUEST_ID))
            assertEquals("browser/1.0", provided.get(StringKey.USER_AGENT))
            assertEquals("/api/v1/orders", provided.get(StringKey.PATH))
        }
    }

    @Test
    fun `provider returns empty context after thread local is cleaned up`() = runTest {
        withObservabilityContext(ObservabilityContext.builder().put(StringKey.REQUEST_ID, "x").build()) {
            // inside, context is set
        }
        // after scope, thread-local is restored to null
        val provided = provider.provide()
        assertTrue(provided.asMap().isEmpty())
    }
}

