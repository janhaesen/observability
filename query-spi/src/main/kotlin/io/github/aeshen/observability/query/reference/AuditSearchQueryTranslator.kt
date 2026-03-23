package io.github.aeshen.observability.query.reference

import io.github.aeshen.observability.query.AuditComparisonOperator
import io.github.aeshen.observability.query.AuditCriterion
import io.github.aeshen.observability.query.AuditField
import io.github.aeshen.observability.query.AuditLogicalOperator
import io.github.aeshen.observability.query.AuditPage
import io.github.aeshen.observability.query.AuditSearchQuery
import io.github.aeshen.observability.query.AuditSort
import io.github.aeshen.observability.query.AuditSortDirection
import io.github.aeshen.observability.query.AuditTextQuery
import io.github.aeshen.observability.query.AuditValue

/**
 * Backend-neutral translation output produced from an [AuditSearchQuery].
 */
data class TranslatedAuditQuery<P, S, T>(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val filter: P?,
    val text: T?,
    val sort: List<S>,
    val page: AuditPage,
)

/**
 * Maps [AuditField] values into backend-specific field identifiers.
 */
fun interface AuditFieldMapper<F> {
    fun map(field: AuditField): F
}

/**
 * Builds backend predicates from typed criteria.
 */
interface AuditPredicateFactory<F, P> {
    fun comparison(
        field: F,
        operator: AuditComparisonOperator,
        value: AuditValue,
    ): P

    fun exists(
        field: F,
        shouldExist: Boolean,
    ): P

    fun group(
        operator: AuditLogicalOperator,
        criteria: List<P>,
    ): P
}

/**
 * Builds backend sort clauses.
 */
fun interface AuditSortFactory<F, S> {
    fun sort(
        field: F,
        direction: AuditSortDirection,
    ): S
}

/**
 * Builds backend text-search clauses.
 */
fun interface AuditTextFactory<T> {
    fun text(query: AuditTextQuery): T
}

/**
 * Reusable translator that converts [AuditSearchQuery] into backend-specific clauses.
 */
class AuditSearchQueryTranslator<F, P, S, T>(
    private val fieldMapper: AuditFieldMapper<F>,
    private val predicateFactory: AuditPredicateFactory<F, P>,
    private val sortFactory: AuditSortFactory<F, S>,
    private val textFactory: AuditTextFactory<T>,
) {
    fun translate(query: AuditSearchQuery): TranslatedAuditQuery<P, S, T> {
        val filter = translateCriteria(query.criteria)
        val text = query.text?.let(textFactory::text)
        val sort = query.sort.map(::translateSort)

        return TranslatedAuditQuery(
            fromEpochMillis = query.fromEpochMillis,
            toEpochMillis = query.toEpochMillis,
            filter = filter,
            text = text,
            sort = sort,
            page = query.page,
        )
    }

    private fun translateCriteria(criteria: List<AuditCriterion>): P? {
        if (criteria.isEmpty()) {
            return null
        }

        val mapped = criteria.map(::translateCriterion)
        return if (mapped.size == 1) {
            mapped.first()
        } else {
            predicateFactory.group(AuditLogicalOperator.AND, mapped)
        }
    }

    private fun translateSort(sort: AuditSort): S =
        sortFactory.sort(
            field = fieldMapper.map(sort.field),
            direction = sort.direction,
        )

    private fun translateCriterion(criterion: AuditCriterion): P =
        when (criterion) {
            is AuditCriterion.Comparison ->
                predicateFactory.comparison(
                    field = fieldMapper.map(criterion.field),
                    operator = criterion.operator,
                    value = criterion.value,
                )

            is AuditCriterion.Exists ->
                predicateFactory.exists(
                    field = fieldMapper.map(criterion.field),
                    shouldExist = criterion.shouldExist,
                )

            is AuditCriterion.Group ->
                predicateFactory.group(
                    operator = criterion.operator,
                    criteria = criterion.criteria.map(::translateCriterion),
                )
        }
}
