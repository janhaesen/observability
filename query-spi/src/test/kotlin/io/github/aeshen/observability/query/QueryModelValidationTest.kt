package io.github.aeshen.observability.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryModelValidationTest {
    @Test
    fun `audit search query validates model constraints`() {
        assertFailsWith<IllegalArgumentException> {
            AuditSearchQuery(fromEpochMillis = -1, toEpochMillis = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditSearchQuery(fromEpochMillis = 10, toEpochMillis = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditCriterion.Group(operator = AuditLogicalOperator.AND, criteria = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            AuditTextQuery("   ")
        }
        assertFailsWith<IllegalArgumentException> {
            AuditField.context("   ")
        }
        assertFailsWith<IllegalArgumentException> {
            AuditField.metadata("")
        }
    }

    @Test
    fun `audit field helpers create canonical context and metadata prefixes`() {
        assertEquals(AuditField.custom("context.request_id"), AuditField.context("request_id"))
        assertEquals(AuditField.custom("metadata.ingestedAt"), AuditField.metadata("ingestedAt"))
    }

    @Test
    fun `audit search query defaults to offset pagination`() {
        val query =
            AuditSearchQuery(
                fromEpochMillis = 10,
                toEpochMillis = 20,
            )

        assertEquals(AuditPagination.Offset(), query.pagination)
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
    fun `audit pagination offset validates constraints`() {
        assertFailsWith<IllegalArgumentException> {
            AuditPagination.Offset(limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditPagination.Offset(offset = -1)
        }
        val valid = AuditPagination.Offset(limit = 50, offset = 100)
        assertEquals(50, valid.limit)
        assertEquals(100, valid.offset)
    }

    @Test
    fun `audit pagination cursor validates constraints`() {
        assertFailsWith<IllegalArgumentException> {
            AuditPagination.Cursor(limit = 0, after = "token")
        }
        assertFailsWith<IllegalArgumentException> {
            AuditPagination.Cursor(limit = 10, after = "   ")
        }
        val valid = AuditPagination.Cursor(limit = 25, after = "opaque-token-abc")
        assertEquals(25, valid.limit)
        assertEquals("opaque-token-abc", valid.after)
    }

    @Test
    fun `audit search query stores explicit pagination`() {
        val query =
            AuditSearchQuery(
                fromEpochMillis = 0,
                toEpochMillis = 100,
                pagination = AuditPagination.Cursor(limit = 20, after = "my-cursor"),
            )
        assertEquals(AuditPagination.Cursor(limit = 20, after = "my-cursor"), query.pagination)
    }

    @Test
    fun `audit query result stores next cursor`() {
        val record =
            AuditRecord(
                id = "id-1",
                timestampEpochMillis = 1,
                level = "INFO",
                event = "event",
                message = null,
                context = emptyMap(),
            )
        val withCursor = AuditQueryResult(records = listOf(record), total = 1, nextCursor = "eyJpZCI6Imxhc3QifQ==")
        assertEquals("eyJpZCI6Imxhc3QifQ==", withCursor.nextCursor)

        val withoutCursor = AuditQueryResult(records = listOf(record), total = 1)
        assertEquals(null, withoutCursor.nextCursor)
    }
}
