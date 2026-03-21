package io.github.aeshen.observability.query

import kotlin.test.Test
import kotlin.test.assertFailsWith

class QueryModelValidationTest {
    @Test
    fun `audit query validates time window and pagination`() {
        assertFailsWith<IllegalArgumentException> {
            AuditQuery(fromEpochMillis = -1, toEpochMillis = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditQuery(fromEpochMillis = 10, toEpochMillis = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditQuery(fromEpochMillis = 1, toEpochMillis = 10, limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditQuery(fromEpochMillis = 1, toEpochMillis = 10, offset = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditQuery(
                fromEpochMillis = 1,
                toEpochMillis = 10,
                filters = mapOf("" to "ERROR"),
            )
        }
    }

    @Test
    fun `audit record validates identity and key fields`() {
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(
                id = "",
                timestampEpochMillis = 1,
                level = "INFO",
                event = "event",
                message = null,
                context = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(
                id = "id-1",
                timestampEpochMillis = -1,
                level = "INFO",
                event = "event",
                message = null,
                context = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(
                id = "id-1",
                timestampEpochMillis = 1,
                level = "",
                event = "event",
                message = null,
                context = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(
                id = "id-1",
                timestampEpochMillis = 1,
                level = "INFO",
                event = "",
                message = null,
                context = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(
                id = "id-1",
                timestampEpochMillis = 1,
                level = "INFO",
                event = "event",
                message = null,
                context = mapOf("" to "v"),
            )
        }
    }

    @Test
    fun `audit query result validates total constraints`() {
        val record =
            AuditRecord(
                id = "id-1",
                timestampEpochMillis = 1,
                level = "INFO",
                event = "event",
                message = null,
                context = emptyMap(),
            )

        assertFailsWith<IllegalArgumentException> {
            AuditQueryResult(records = listOf(record), total = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditQueryResult(records = listOf(record), total = 0)
        }
    }
}
