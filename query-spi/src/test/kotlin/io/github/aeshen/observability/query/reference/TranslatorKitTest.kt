package io.github.aeshen.observability.query.reference

import io.github.aeshen.observability.query.AuditComparisonOperator
import io.github.aeshen.observability.query.AuditCriterion
import io.github.aeshen.observability.query.AuditField
import io.github.aeshen.observability.query.AuditLogicalOperator
import io.github.aeshen.observability.query.AuditPage
import io.github.aeshen.observability.query.AuditSearchQuery
import io.github.aeshen.observability.query.AuditSort
import io.github.aeshen.observability.query.AuditSortDirection
import io.github.aeshen.observability.query.AuditTextMatchMode
import io.github.aeshen.observability.query.AuditTextQuery
import io.github.aeshen.observability.query.AuditValue
import io.github.aeshen.observability.query.reference.demo.ReferenceBackendQuery
import io.github.aeshen.observability.query.reference.demo.ReferenceBackendTranslator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranslatorKitTest {
    @Test
    fun `standard field parser detects canonical fields and prefixes`() {
        assertEquals(StandardAuditField.Id, StandardAuditFieldMappings.resolve(AuditField.ID))
        assertEquals(
            StandardAuditField.Context("request_id"),
            StandardAuditFieldMappings.resolve(AuditField.context("request_id")),
        )
        assertEquals(
            StandardAuditField.Metadata("ingestedAt"),
            StandardAuditFieldMappings.resolve(AuditField.metadata("ingestedAt")),
        )
        assertEquals(
            StandardAuditField.Custom("vendor.tenant"),
            StandardAuditFieldMappings.resolve(AuditField.custom("vendor.tenant")),
        )
    }

    @Test
    fun `translator kit maps criteria sort and text using backend factories`() {
        val translator = createQueryTranslator()

        val translated =
            translator.translate(
                AuditSearchQuery(
                    fromEpochMillis = 10,
                    toEpochMillis = 20,
                    page = AuditPage(limit = 25, offset = 5),
                    criteria =
                        listOf(
                            AuditCriterion.Comparison(
                                field = AuditField.LEVEL,
                                operator = AuditComparisonOperator.EQ,
                                value = AuditValue.Text("ERROR"),
                            ),
                            AuditCriterion.Group(
                                operator = AuditLogicalOperator.OR,
                                criteria =
                                    listOf(
                                        AuditCriterion.Exists(
                                            field = AuditField.context("request_id"),
                                        ),
                                        AuditCriterion.Comparison(
                                            field = AuditField.metadata("source"),
                                            operator = AuditComparisonOperator.NEQ,
                                            value = AuditValue.Text("api"),
                                        ),
                                    ),
                            ),
                        ),
                    text = AuditTextQuery(query = "payment", mode = AuditTextMatchMode.PREFIX),
                    sort =
                        listOf(
                            AuditSort(AuditField.TIMESTAMP_EPOCH_MILLIS, AuditSortDirection.DESC),
                            AuditSort(AuditField.LEVEL, AuditSortDirection.ASC),
                        ),
                ),
            )

        assertEquals(10, translated.fromEpochMillis)
        assertEquals(20, translated.toEpochMillis)
        assertEquals(
            "group(AND,cmp(mapped.level,EQ,Text(value=ERROR));group(OR,exists" +
                "(mapped.context.request_id,true);cmp(mapped.metadata.source,NEQ,Text(value=api))))",
            translated.filter,
        )
        assertEquals("text(PREFIX,false,payment)", translated.text)
        assertEquals(
            listOf(
                "sort(mapped.timestampEpochMillis,DESC)",
                "sort(mapped.level,ASC)",
            ),
            translated.sort,
        )
        assertEquals(AuditPage(limit = 25, offset = 5), translated.page)
    }

    private fun createQueryTranslator(): AuditSearchQueryTranslator<String, String, String, String> {
        val translator =
            AuditSearchQueryTranslator(
                fieldMapper = { field -> "mapped.${field.key}" },
                predicateFactory =
                    object : AuditPredicateFactory<String, String> {
                        override fun comparison(
                            field: String,
                            operator: AuditComparisonOperator,
                            value: AuditValue,
                        ): String = "cmp($field,$operator,$value)"

                        override fun exists(
                            field: String,
                            shouldExist: Boolean,
                        ): String = "exists($field,$shouldExist)"

                        override fun group(
                            operator: AuditLogicalOperator,
                            criteria: List<String>,
                        ): String = "group($operator,${criteria.joinToString(";")})"
                    },
                sortFactory = { field, direction -> "sort($field,$direction)" },
                textFactory = { text -> "text(${text.mode},${text.caseSensitive},${text.query})" },
            )
        return translator
    }

    @Test
    fun `reference translator demonstrates end to end audit search query translation`() {
        val translated: ReferenceBackendQuery =
            ReferenceBackendTranslator.translate(
                AuditSearchQuery(
                    fromEpochMillis = 1_710_000_000_000,
                    toEpochMillis = 1_710_003_600_000,
                    page = AuditPage(limit = 100, offset = 200),
                    criteria =
                        listOf(
                            AuditCriterion.Comparison(
                                field = AuditField.LEVEL,
                                operator = AuditComparisonOperator.EQ,
                                value = AuditValue.Text("ERROR"),
                            ),
                            AuditCriterion.Group(
                                operator = AuditLogicalOperator.OR,
                                criteria =
                                    listOf(
                                        AuditCriterion.Comparison(
                                            field = AuditField.context("request_id"),
                                            operator = AuditComparisonOperator.EQ,
                                            value = AuditValue.Text("req-123"),
                                        ),
                                        AuditCriterion.Comparison(
                                            field = AuditField.metadata("source"),
                                            operator = AuditComparisonOperator.IN,
                                            value = AuditValue.TextList(listOf("api", "worker")),
                                        ),
                                    ),
                            ),
                        ),
                    text = AuditTextQuery(query = "payment", mode = AuditTextMatchMode.CONTAINS),
                    sort =
                        listOf(
                            AuditSort(field = AuditField.TIMESTAMP_EPOCH_MILLIS, direction = AuditSortDirection.DESC),
                            AuditSort(field = AuditField.ID, direction = AuditSortDirection.ASC),
                        ),
                ),
            )

        assertEquals(1_710_000_000_000, translated.fromEpochMillis)
        assertEquals(1_710_003_600_000, translated.toEpochMillis)
        val whereClause = assertNotNull(translated.whereClause)
        assertTrue(whereClause.contains("severity = 'ERROR'"))
        assertTrue(whereClause.contains("ctx.request_id = 'req-123'"))
        assertTrue(whereClause.contains("meta.source IN ('api','worker')"))
        assertTrue(whereClause.contains("TEXT(mode=CONTAINS, query='payment', caseSensitive=false)"))
        assertTrue(whereClause.contains(" AND "))
        assertTrue(whereClause.contains(" OR "))
        assertEquals(
            listOf("event_ts_ms DESC", "audit_id ASC"),
            translated.sortClauses,
        )
        assertEquals(100, translated.limit)
        assertEquals(200, translated.offset)
    }
}
