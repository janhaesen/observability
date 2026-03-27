package io.github.aeshen.observability.query.reference.demo

import io.github.aeshen.observability.query.AuditComparisonOperator
import io.github.aeshen.observability.query.AuditLogicalOperator
import io.github.aeshen.observability.query.AuditSearchQuery
import io.github.aeshen.observability.query.AuditTextMatchMode
import io.github.aeshen.observability.query.AuditValue
import io.github.aeshen.observability.query.reference.AuditPredicateFactory
import io.github.aeshen.observability.query.reference.AuditSearchQueryTranslator
import io.github.aeshen.observability.query.reference.StandardAuditFieldMapper

/**
 * Demonstrates one complete translation path from [AuditSearchQuery] to backend clauses.
 */
data class ReferenceBackendQuery(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val whereClause: String?,
    val sortClauses: List<String>,
    val limit: Int,
    val offset: Int,
)

object ReferenceBackendTranslator {
    private val translator =
        AuditSearchQueryTranslator(
            fieldMapper =
            StandardAuditFieldMapper(
                idField = "audit_id",
                timestampField = "event_ts_ms",
                levelField = "severity",
                eventField = "event_name",
                messageField = "message_text",
                contextPrefix = "ctx.",
                metadataPrefix = "meta.",
            ),
            predicateFactory = ReferencePredicateFactory,
            sortFactory = { field, direction -> "$field ${direction.name}" },
            textFactory = { text -> referenceTextClause(text) },
        )

    fun translate(query: AuditSearchQuery): ReferenceBackendQuery {
        val translated = translator.translate(query)
        val whereClause = combineWhereClauses(translated.filter, translated.text)

        return ReferenceBackendQuery(
            fromEpochMillis = translated.fromEpochMillis,
            toEpochMillis = translated.toEpochMillis,
            whereClause = whereClause,
            sortClauses = translated.sort,
            limit = translated.page.limit,
            offset = translated.page.offset,
        )
    }

    private fun combineWhereClauses(
        filter: String?,
        text: String?,
    ): String? {
        val clauses = listOfNotNull(filter, text)
        if (clauses.isEmpty()) {
            return null
        }

        return clauses.joinToString(" AND ") { "($it)" }
    }

    private fun referenceTextClause(text: io.github.aeshen.observability.query.AuditTextQuery): String {
        val escapedQuery = escapeSingleQuotes(text.query)
        val mode =
            when (text.mode) {
                AuditTextMatchMode.CONTAINS -> "CONTAINS"
                AuditTextMatchMode.EXACT -> "EXACT"
                AuditTextMatchMode.PREFIX -> "PREFIX"
            }
        return "TEXT(mode=$mode, query='$escapedQuery', caseSensitive=${text.caseSensitive})"
    }

    private object ReferencePredicateFactory : AuditPredicateFactory<String, String> {
        override fun comparison(
            field: String,
            operator: AuditComparisonOperator,
            value: AuditValue,
        ): String =
            when (operator) {
                AuditComparisonOperator.EQ -> "$field = ${renderScalarValue(value)}"
                AuditComparisonOperator.NEQ -> "$field != ${renderScalarValue(value)}"
                AuditComparisonOperator.GT -> "$field > ${renderScalarValue(value)}"
                AuditComparisonOperator.GTE -> "$field >= ${renderScalarValue(value)}"
                AuditComparisonOperator.LT -> "$field < ${renderScalarValue(value)}"
                AuditComparisonOperator.LTE -> "$field <= ${renderScalarValue(value)}"
                AuditComparisonOperator.IN -> "$field IN ${renderInValue(value)}"
                AuditComparisonOperator.CONTAINS -> "$field LIKE ${renderLikeValue(value, prefix = "%", suffix = "%")}"
                AuditComparisonOperator.STARTS_WITH -> "$field LIKE ${renderLikeValue(value, suffix = "%")}"
                AuditComparisonOperator.ENDS_WITH -> "$field LIKE ${renderLikeValue(value, prefix = "%")}"
            }

        override fun exists(
            field: String,
            shouldExist: Boolean,
        ): String =
            if (shouldExist) {
                "$field IS NOT NULL"
            } else {
                "$field IS NULL"
            }

        override fun group(
            operator: AuditLogicalOperator,
            criteria: List<String>,
        ): String {
            val token =
                when (operator) {
                    AuditLogicalOperator.AND -> " AND "
                    AuditLogicalOperator.OR -> " OR "
                }
            return criteria.joinToString(token, prefix = "(", postfix = ")") { it }
        }

        private fun renderInValue(value: AuditValue): String {
            require(
                value is AuditValue.TextList
            ) { "IN operator requires AuditValue.TextList in the reference translator." }
            val rendered = value.values.joinToString(",") { "'${escapeSingleQuotes(it)}'" }
            return "($rendered)"
        }

        private fun renderLikeValue(
            value: AuditValue,
            prefix: String = "",
            suffix: String = "",
        ): String {
            require(value is AuditValue.Text) {
                "String pattern operators require AuditValue.Text in the reference translator."
            }
            return "'${escapeSingleQuotes(prefix + value.value + suffix)}'"
        }

        private fun renderScalarValue(value: AuditValue): String =
            when (value) {
                is AuditValue.Text -> {
                    "'${escapeSingleQuotes(value.value)}'"
                }

                is AuditValue.Number -> {
                    value.value.toString()
                }

                is AuditValue.Decimal -> {
                    value.value.toString()
                }

                is AuditValue.Bool -> {
                    value.value.toString().uppercase()
                }

                is AuditValue.TextList -> {
                    error("Text list values are only supported with the IN operator in the reference translator.")
                }
            }
    }

    private fun escapeSingleQuotes(value: String): String = value.replace("'", "''")
}
