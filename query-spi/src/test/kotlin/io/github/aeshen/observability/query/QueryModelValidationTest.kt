package io.github.aeshen.observability.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun `audit search query validates model constraints`() {
        assertFailsWith<IllegalArgumentException> {
            AuditSearchQuery(fromEpochMillis = -1, toEpochMillis = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditSearchQuery(fromEpochMillis = 10, toEpochMillis = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditPage(limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditPage(limit = 10, offset = -1)
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
    fun `legacy audit query maps into typed audit search query`() {
        val legacy =
            AuditQuery(
                fromEpochMillis = 10,
                toEpochMillis = 20,
                limit = 25,
                offset = 5,
                filters = mapOf("level" to "ERROR"),
                freeText = " payment ",
            )

        val typed = legacy.toSearchQuery()

        assertEquals(10, typed.fromEpochMillis)
        assertEquals(20, typed.toEpochMillis)
        assertEquals(AuditPage(limit = 25, offset = 5), typed.page)
        assertEquals(
            listOf(
                AuditCriterion.Comparison(
                    field = AuditField.custom("level"),
                    operator = AuditComparisonOperator.EQ,
                    value = AuditValue.Text("ERROR"),
                ),
            ),
            typed.criteria,
        )
        assertEquals(AuditTextQuery("payment"), typed.text)
    }

    @Test
    fun `legacy mapper drops blank free text`() {
        val legacy =
            AuditQuery(
                fromEpochMillis = 10,
                toEpochMillis = 20,
                freeText = "   ",
            )

        val typed = legacy.toSearchQuery()

        assertNull(typed.text)
    }

    @Test
    fun `legacy mapper preserves canonical prefixed filter fields`() {
        val legacy =
            AuditQuery(
                fromEpochMillis = 10,
                toEpochMillis = 20,
                filters = mapOf(
                    "context.request_id" to "req-123",
                    "metadata.ingestedAt" to "1710000000000",
                ),
            )

        val typed = legacy.toSearchQuery()

        assertEquals(
            listOf(
                AuditCriterion.Comparison(
                    field = AuditField.context("request_id"),
                    operator = AuditComparisonOperator.EQ,
                    value = AuditValue.Text("req-123"),
                ),
                AuditCriterion.Comparison(
                    field = AuditField.metadata("ingestedAt"),
                    operator = AuditComparisonOperator.EQ,
                    value = AuditValue.Text("1710000000000"),
                ),
            ),
            typed.criteria,
        )
    }

    @Test
    fun `typed service adapter delegates using typed mapping`() {
        val service =
            object : AuditSearchQueryService {
                override fun search(query: AuditSearchQuery): AuditQueryResult {
                    assertEquals(AuditPage(limit = 7, offset = 3), query.page)
                    assertEquals(AuditTextQuery("billing"), query.text)
                    return AuditQueryResult(records = emptyList(), total = 1)
                }
            }

        val result =
            service
                .asLegacyService()
                .search(
                    AuditQuery(
                        fromEpochMillis = 1,
                        toEpochMillis = 2,
                        limit = 7,
                        offset = 3,
                        freeText = "billing",
                    ),
                )

        assertEquals(1, result.total)
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
