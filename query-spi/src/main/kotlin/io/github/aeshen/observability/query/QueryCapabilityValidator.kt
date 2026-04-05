package io.github.aeshen.observability.query

/**
 * Describes a single capability requirement that was not satisfied by a backend's declared
 * [QueryCapabilityDescriptor].
 *
 * @property capability The unsupported [QueryCapability].
 * @property detail Human-readable explanation of which part of the query requires the capability.
 */
data class QueryCapabilityViolation(
    val capability: QueryCapability,
    val detail: String,
)

/**
 * Thrown by [QueryCapabilityValidator.validate] when an [AuditSearchQuery] requires one or more
 * capabilities that the backend has not declared as supported.
 *
 * @property violations The full list of unsatisfied capability requirements.
 */
class UnsupportedQueryCapabilityException(
    val violations: List<QueryCapabilityViolation>,
) : IllegalArgumentException(
        buildMessage(violations),
    ) {
    init {
        require(violations.isNotEmpty()) { "violations must not be empty." }
    }

    private companion object {
        fun buildMessage(violations: List<QueryCapabilityViolation>): String {
            val lines = violations.joinToString(separator = "\n  - ") { "${it.capability}: ${it.detail}" }
            return "Query requires unsupported capabilities:\n  - $lines"
        }
    }
}

private val DEFAULT_SORT = listOf(AuditSort(field = AuditField.TIMESTAMP_EPOCH_MILLIS))

/**
 * Validates an [AuditSearchQuery] against a [QueryCapabilityDescriptor].
 *
 * Use [check] to obtain a (possibly empty) list of violations without throwing.
 * Use [validate] to throw [UnsupportedQueryCapabilityException] on the first unsatisfied requirement.
 *
 * **Detection rules:**
 * | Capability | Triggered when |
 * |---|---|
 * | [QueryCapability.TEXT_SEARCH] | `query.text != null` |
 * | [QueryCapability.SORT] | sort list differs from the default (single timestamp DESC) |
 * | [QueryCapability.NESTED_CRITERIA] | any criterion tree node is an [AuditCriterion.Group] |
 * | [QueryCapability.OFFSET_PAGINATION] | resolved pagination is [AuditPagination.Offset] |
 * | [QueryCapability.CURSOR_PAGINATION] | resolved pagination is [AuditPagination.Cursor] |
 *
 * @see QueryCapabilityDescriptor
 * @see QueryCapabilityAware
 */
object QueryCapabilityValidator {
    /**
     * Returns every [QueryCapabilityViolation] found for [query] against [capabilities].
     * An empty list means the query is fully compatible with the declared capabilities.
     */
    fun check(
        query: AuditSearchQuery,
        capabilities: QueryCapabilityDescriptor,
    ): List<QueryCapabilityViolation> {
        val violations = mutableListOf<QueryCapabilityViolation>()

        if (query.text != null && !capabilities.supports(QueryCapability.TEXT_SEARCH)) {
            violations +=
                QueryCapabilityViolation(
                    capability = QueryCapability.TEXT_SEARCH,
                    detail = "query.text is set but the backend does not support text search.",
                )
        }

        if (query.sort != DEFAULT_SORT && !capabilities.supports(QueryCapability.SORT)) {
            violations +=
                QueryCapabilityViolation(
                    capability = QueryCapability.SORT,
                    detail =
                        "query.sort contains a non-default sort order but the backend does not " +
                            "support custom sorting.",
                )
        }

        if (hasNestedCriteria(query.criteria) && !capabilities.supports(QueryCapability.NESTED_CRITERIA)) {
            violations +=
                QueryCapabilityViolation(
                    capability = QueryCapability.NESTED_CRITERIA,
                    detail =
                        "query.criteria contains an AuditCriterion.Group but the backend does not " +
                            "support nested criteria.",
                )
        }

        when (query.resolvedPagination) {
            is AuditPagination.Offset -> {
                if (!capabilities.supports(QueryCapability.OFFSET_PAGINATION)) {
                    violations +=
                        QueryCapabilityViolation(
                            capability = QueryCapability.OFFSET_PAGINATION,
                            detail =
                                "resolved pagination is Offset but the backend does not " +
                                    "support offset pagination.",
                        )
                }
            }

            is AuditPagination.Cursor -> {
                if (!capabilities.supports(QueryCapability.CURSOR_PAGINATION)) {
                    violations +=
                        QueryCapabilityViolation(
                            capability = QueryCapability.CURSOR_PAGINATION,
                            detail =
                                "resolved pagination is Cursor but the backend does not " +
                                    "support cursor pagination.",
                        )
                }
            }
        }

        return violations
    }

    /**
     * Validates [query] against [capabilities] and throws [UnsupportedQueryCapabilityException]
     * if any violations are found.
     */
    fun validate(
        query: AuditSearchQuery,
        capabilities: QueryCapabilityDescriptor,
    ) {
        val violations = check(query, capabilities)
        if (violations.isNotEmpty()) {
            throw UnsupportedQueryCapabilityException(violations)
        }
    }

    private fun hasNestedCriteria(criteria: List<AuditCriterion>): Boolean =
        criteria.any { criterion ->
            when (criterion) {
                is AuditCriterion.Group -> true
                is AuditCriterion.Comparison -> false
                is AuditCriterion.Exists -> false
            }
        }
}
