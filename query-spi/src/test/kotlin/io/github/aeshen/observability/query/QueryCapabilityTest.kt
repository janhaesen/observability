package io.github.aeshen.observability.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryCapabilityTest {
    // ------------------------------------------------------------------ descriptor

    @Test
    fun `FULL descriptor supports every capability`() {
        val descriptor = QueryCapabilityDescriptor.FULL
        QueryCapability.entries.forEach { capability ->
            assertTrue(descriptor.supports(capability), "FULL should support $capability")
        }
    }

    @Test
    fun `MINIMAL descriptor supports only offset pagination`() {
        val descriptor = QueryCapabilityDescriptor.MINIMAL
        assertTrue(descriptor.supports(QueryCapability.OFFSET_PAGINATION))
        assertFalse(descriptor.supports(QueryCapability.CURSOR_PAGINATION))
        assertFalse(descriptor.supports(QueryCapability.TEXT_SEARCH))
        assertFalse(descriptor.supports(QueryCapability.SORT))
        assertFalse(descriptor.supports(QueryCapability.NESTED_CRITERIA))
        assertFalse(descriptor.supports(QueryCapability.PROJECTIONS))
    }

    @Test
    fun `custom descriptor reflects declared set`() {
        val descriptor = QueryCapabilityDescriptor(setOf(QueryCapability.SORT, QueryCapability.TEXT_SEARCH))
        assertTrue(descriptor.supports(QueryCapability.SORT))
        assertTrue(descriptor.supports(QueryCapability.TEXT_SEARCH))
        assertFalse(descriptor.supports(QueryCapability.CURSOR_PAGINATION))
    }

    // ------------------------------------------------------------------ validator – no violations

    @Test
    fun `check returns empty list for minimal query against FULL capabilities`() {
        val query = baseQuery()
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.FULL)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `check returns empty list for minimal query against MINIMAL capabilities`() {
        val query = baseQuery()
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.MINIMAL)
        assertTrue(violations.isEmpty())
    }

    // ------------------------------------------------------------------ validator – TEXT_SEARCH

    @Test
    fun `check detects TEXT_SEARCH violation`() {
        val query = baseQuery(text = AuditTextQuery("hello"))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.MINIMAL)
        assertEquals(1, violations.size)
        assertEquals(QueryCapability.TEXT_SEARCH, violations.single().capability)
    }

    @Test
    fun `check accepts text query when TEXT_SEARCH is supported`() {
        val query = baseQuery(text = AuditTextQuery("hello"))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.FULL)
        assertTrue(violations.isEmpty())
    }

    // ------------------------------------------------------------------ validator – SORT

    @Test
    fun `check detects SORT violation for non-default sort`() {
        val query = baseQuery(sort = listOf(AuditSort(AuditField.LEVEL, AuditSortDirection.ASC)))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.MINIMAL)
        assertEquals(1, violations.size)
        assertEquals(QueryCapability.SORT, violations.single().capability)
    }

    @Test
    fun `check does not flag default sort as SORT violation`() {
        val query = baseQuery()
        val violations =
            QueryCapabilityValidator.check(
                query,
                QueryCapabilityDescriptor(setOf(QueryCapability.OFFSET_PAGINATION)),
            )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `check accepts custom sort when SORT is supported`() {
        val query = baseQuery(sort = listOf(AuditSort(AuditField.LEVEL, AuditSortDirection.ASC)))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.FULL)
        assertTrue(violations.isEmpty())
    }

    // ------------------------------------------------------------------ validator – NESTED_CRITERIA

    @Test
    fun `check detects NESTED_CRITERIA violation`() {
        val group =
            AuditCriterion.Group(
                operator = AuditLogicalOperator.OR,
                criteria =
                    listOf(
                        AuditCriterion.Comparison(
                            AuditField.LEVEL,
                            AuditComparisonOperator.EQ,
                            AuditValue.Text("ERROR"),
                        ),
                        AuditCriterion.Comparison(
                            AuditField.LEVEL,
                            AuditComparisonOperator.EQ,
                            AuditValue.Text("WARN"),
                        ),
                    ),
            )
        val query = baseQuery(criteria = listOf(group))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.MINIMAL)
        assertEquals(1, violations.size)
        assertEquals(QueryCapability.NESTED_CRITERIA, violations.single().capability)
    }

    @Test
    fun `check accepts flat criteria without NESTED_CRITERIA`() {
        val flat = AuditCriterion.Comparison(AuditField.LEVEL, AuditComparisonOperator.EQ, AuditValue.Text("ERROR"))
        val query = baseQuery(criteria = listOf(flat))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.MINIMAL)
        assertTrue(violations.isEmpty())
    }

    // ------------------------------------------------------------------ validator – OFFSET_PAGINATION

    @Test
    fun `check detects OFFSET_PAGINATION violation`() {
        val query = baseQuery(pagination = AuditPagination.Offset(limit = 10, offset = 5))
        val violations =
            QueryCapabilityValidator.check(
                query,
                QueryCapabilityDescriptor(setOf(QueryCapability.CURSOR_PAGINATION)),
            )
        assertEquals(1, violations.size)
        assertEquals(QueryCapability.OFFSET_PAGINATION, violations.single().capability)
    }

    @Test
    fun `check accepts offset pagination when OFFSET_PAGINATION is supported`() {
        val query = baseQuery(pagination = AuditPagination.Offset(limit = 50))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.FULL)
        assertTrue(violations.isEmpty())
    }

    // ------------------------------------------------------------------ validator – CURSOR_PAGINATION

    @Test
    fun `check detects CURSOR_PAGINATION violation`() {
        val query = baseQuery(pagination = AuditPagination.Cursor(after = "eyJpZCI6Imxhc3QifQ=="))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.MINIMAL)
        assertEquals(1, violations.size)
        assertEquals(QueryCapability.CURSOR_PAGINATION, violations.single().capability)
    }

    @Test
    fun `check accepts cursor pagination when CURSOR_PAGINATION is supported`() {
        val query = baseQuery(pagination = AuditPagination.Cursor(after = "eyJpZCI6Imxhc3QifQ=="))
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.FULL)
        assertTrue(violations.isEmpty())
    }

    // ------------------------------------------------------------------ validator – multiple violations

    @Test
    fun `check returns multiple violations when several features are unsupported`() {
        val query =
            baseQuery(
                text = AuditTextQuery("search term"),
                pagination = AuditPagination.Cursor(after = "abc"),
            )
        val violations = QueryCapabilityValidator.check(query, QueryCapabilityDescriptor.MINIMAL)
        val capabilities = violations.map { it.capability }
        assertTrue(QueryCapability.TEXT_SEARCH in capabilities)
        assertTrue(QueryCapability.CURSOR_PAGINATION in capabilities)
    }

    // ------------------------------------------------------------------ validate() throws

    @Test
    fun `validate throws UnsupportedQueryCapabilityException on violations`() {
        val query = baseQuery(text = AuditTextQuery("hello"))
        val ex =
            assertFailsWith<UnsupportedQueryCapabilityException> {
                QueryCapabilityValidator.validate(query, QueryCapabilityDescriptor.MINIMAL)
            }
        assertEquals(1, ex.violations.size)
        assertEquals(QueryCapability.TEXT_SEARCH, ex.violations.single().capability)
        assertIs<IllegalArgumentException>(ex)
    }

    @Test
    fun `validate does not throw when query is compatible`() {
        val query = baseQuery()
        QueryCapabilityValidator.validate(query, QueryCapabilityDescriptor.MINIMAL)
    }

    // ------------------------------------------------------------------ UnsupportedQueryCapabilityException

    @Test
    fun `UnsupportedQueryCapabilityException requires non-empty violations`() {
        assertFailsWith<IllegalArgumentException> {
            UnsupportedQueryCapabilityException(emptyList())
        }
    }

    @Test
    fun `UnsupportedQueryCapabilityException message contains capability names`() {
        val ex =
            UnsupportedQueryCapabilityException(
                listOf(
                    QueryCapabilityViolation(QueryCapability.TEXT_SEARCH, "text is set"),
                    QueryCapabilityViolation(QueryCapability.CURSOR_PAGINATION, "cursor used"),
                ),
            )
        assertTrue(ex.message!!.contains("TEXT_SEARCH"))
        assertTrue(ex.message!!.contains("CURSOR_PAGINATION"))
    }

    // ------------------------------------------------------------------ QueryCapabilityAware contract

    @Test
    fun `QueryCapabilityAware implementation exposes capabilities`() {
        val service: AuditSearchQueryService =
            object : AuditSearchQueryService, QueryCapabilityAware {
                override val capabilities = QueryCapabilityDescriptor.FULL

                override fun search(query: AuditSearchQuery): AuditQueryResult =
                    AuditQueryResult(records = emptyList(), total = 0)
            }

        val caps = (service as? QueryCapabilityAware)?.capabilities
        assertEquals(QueryCapabilityDescriptor.FULL, caps)
    }

    @Test
    fun `QueryCapabilityAware is absent when not implemented`() {
        val service: AuditSearchQueryService =
            object : AuditSearchQueryService {
                override fun search(query: AuditSearchQuery): AuditQueryResult =
                    AuditQueryResult(records = emptyList(), total = 0)
            }
        val caps = (service as? QueryCapabilityAware)?.capabilities
        assertEquals(null, caps)
    }

    // ------------------------------------------------------------------ helpers

    private fun baseQuery(
        text: AuditTextQuery? = null,
        sort: List<AuditSort> = listOf(AuditSort(field = AuditField.TIMESTAMP_EPOCH_MILLIS)),
        criteria: List<AuditCriterion> = emptyList(),
        pagination: AuditPagination? = null,
    ) = AuditSearchQuery(
        fromEpochMillis = 0L,
        toEpochMillis = 1000L,
        text = text,
        sort = sort,
        criteria = criteria,
        pagination = pagination,
    )
}
