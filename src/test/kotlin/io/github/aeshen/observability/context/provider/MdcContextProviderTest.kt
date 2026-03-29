package io.github.aeshen.observability.context.provider

import org.slf4j.MDC
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MdcContextProviderTest {

    @BeforeTest
    fun setUp() {
        MDC.clear()
    }

    @AfterTest
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `returns empty context when MDC is empty`() {
        val context = MdcContextProvider().provide()
        assertEquals(emptyMap(), context.asMap())
    }

    @Test
    fun `attaches MDC entries with default mdc prefix`() {
        MDC.put("requestId", "abc-123")
        MDC.put("userId", "user-42")

        val context = MdcContextProvider().provide()
        val map = context.asMap()

        val keys = map.keys.map { it.keyName }.toSet()
        assertEquals(setOf("mdc.requestId", "mdc.userId"), keys)

        val values = map.values.filterIsInstance<String>().toSet()
        assertEquals(setOf("abc-123", "user-42"), values)
    }

    @Test
    fun `attaches MDC entries with custom prefix`() {
        MDC.put("traceId", "trace-xyz")

        val context = MdcContextProvider(prefix = "request").provide()
        val map = context.asMap()

        val key = map.keys.single()
        assertEquals("request.traceId", key.keyName)
    }

    @Test
    fun `attaches MDC entries without prefix when empty prefix is given`() {
        MDC.put("sessionId", "sess-99")

        val context = MdcContextProvider(prefix = "").provide()
        val map = context.asMap()

        val key = map.keys.single()
        assertEquals("sessionId", key.keyName)
    }

    @Test
    fun `trims trailing dot from prefix`() {
        MDC.put("foo", "bar")

        val context = MdcContextProvider(prefix = "ns.").provide()
        val key = context.asMap().keys.single()
        assertEquals("ns.foo", key.keyName)
    }

    @Test
    fun `value stored under correct key`() {
        MDC.put("env", "production")

        val context = MdcContextProvider().provide()
        val entry = context.asMap().entries.single()
        assertEquals("mdc.env", entry.key.keyName)
        assertEquals("production", entry.value)
    }

    @Test
    fun `returns empty context after MDC is cleared`() {
        MDC.put("key", "value")
        MDC.clear()

        val context = MdcContextProvider().provide()
        assertNull(context.asMap().entries.firstOrNull())
    }
}
