@file:Suppress("unused")

package io.github.aeshen.observability.query

/**
 * Typed, backend-agnostic query contract intended for long-term API stability.
 *
 * Use [pagination] to control how results are paged. When [pagination] is non-null it takes
 * precedence over the deprecated [page] field. Use [resolvedPagination] to obtain the effective
 * pagination strategy regardless of which field was set.
 */
data class AuditSearchQuery(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    @Deprecated(
        message = "Use pagination instead.",
        replaceWith = ReplaceWith("pagination"),
    )
    val page: AuditPage = AuditPage(),
    val criteria: List<AuditCriterion> = emptyList(),
    val text: AuditTextQuery? = null,
    val sort: List<AuditSort> = listOf(AuditSort(field = AuditField.TIMESTAMP_EPOCH_MILLIS)),
    val pagination: AuditPagination? = null,
) {
    init {
        require(fromEpochMillis >= 0) { "fromEpochMillis must be greater than or equal to 0." }
        require(toEpochMillis >= 0) { "toEpochMillis must be greater than or equal to 0." }
        require(fromEpochMillis <= toEpochMillis) { "fromEpochMillis must be less than or equal to toEpochMillis." }
    }

    /**
     * The effective pagination strategy. When [pagination] is set it is returned directly;
     * otherwise falls back to an [AuditPagination.Offset] derived from the deprecated [page] field.
     */
    val resolvedPagination: AuditPagination
        get() = pagination ?: AuditPagination.Offset(limit = page.limit, offset = page.offset)

    companion object {
        /** Java-friendly entry point for the fluent builder. */
        @JvmStatic
        fun builder(
            fromEpochMillis: Long,
            toEpochMillis: Long,
        ): Builder = Builder(fromEpochMillis, toEpochMillis)
    }

    /**
     * Fluent builder for [AuditSearchQuery] intended for Java callers.
     *
     * ```java
     * AuditSearchQuery query = AuditSearchQuery.builder(from, to)
     *     .pagination(new AuditPagination.Cursor("eyJpZCI6Imxhc3QifQ=="))
     *     .sort(List.of(new AuditSort(AuditField.ID, AuditSortDirection.ASC)))
     *     .build();
     * ```
     */
    class Builder(
        private val fromEpochMillis: Long,
        private val toEpochMillis: Long,
    ) {
        private var criteria: List<AuditCriterion> = emptyList()
        private var text: AuditTextQuery? = null
        private var sort: List<AuditSort> = listOf(AuditSort(field = AuditField.TIMESTAMP_EPOCH_MILLIS))
        private var pagination: AuditPagination? = null

        fun criteria(criteria: List<AuditCriterion>) = apply { this.criteria = criteria }

        fun text(text: AuditTextQuery) = apply { this.text = text }

        fun sort(sort: List<AuditSort>) = apply { this.sort = sort }

        fun pagination(pagination: AuditPagination) = apply { this.pagination = pagination }

        fun build(): AuditSearchQuery =
            AuditSearchQuery(
                fromEpochMillis = fromEpochMillis,
                toEpochMillis = toEpochMillis,
                criteria = criteria,
                text = text,
                sort = sort,
                pagination = pagination,
            )
    }
}

data class AuditPage(
    val limit: Int = 100,
    val offset: Int = 0,
) {
    init {
        require(limit > 0) { "limit must be greater than 0." }
        require(offset >= 0) { "offset must be greater than or equal to 0." }
    }
}

sealed interface AuditCriterion {
    data class Comparison(
        val field: AuditField,
        val operator: AuditComparisonOperator,
        val value: AuditValue,
    ) : AuditCriterion

    data class Exists(
        val field: AuditField,
        val shouldExist: Boolean = true,
    ) : AuditCriterion

    data class Group(
        val operator: AuditLogicalOperator,
        val criteria: List<AuditCriterion>,
    ) : AuditCriterion {
        init {
            require(criteria.isNotEmpty()) { "Group criteria must contain at least one element." }
        }
    }
}

enum class AuditComparisonOperator {
    EQ,
    NEQ,
    GT,
    GTE,
    LT,
    LTE,
    IN,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
}

enum class AuditLogicalOperator {
    AND,
    OR,
}

/**
 * Query field identifier used by [AuditSearchQuery] criteria and sort clauses.
 *
 * Canonical built-in fields remain flat keys such as `id`, `timestampEpochMillis`, `level`, `event`, and `message`.
 *
 * Dynamic event maps should use the following portable naming conventions:
 * - event context fields: `context.<key>`
 * - event metadata fields: `metadata.<key>`
 *
 * Backends may still expose additional vendor-specific fields through [custom].
 */
@JvmInline
value class AuditField(
    val key: String,
) {
    init {
        require(key.isNotBlank()) { "field key must not be blank." }
    }

    companion object {
        val ID = AuditField("id")
        val TIMESTAMP_EPOCH_MILLIS = AuditField("timestampEpochMillis")
        val LEVEL = AuditField("level")
        val EVENT = AuditField("event")
        val MESSAGE = AuditField("message")

        fun custom(key: String): AuditField = AuditField(key)

        fun context(key: String): AuditField = prefixed(prefix = "context.", key = key)

        fun metadata(key: String): AuditField = prefixed(prefix = "metadata.", key = key)

        private fun prefixed(
            prefix: String,
            key: String,
        ): AuditField {
            require(key.isNotBlank()) { "field key must not be blank." }
            return AuditField(prefix + key)
        }
    }
}

sealed interface AuditValue {
    data class Text(
        val value: String,
    ) : AuditValue {
        init {
            require(value.isNotEmpty()) { "text value must not be empty." }
        }
    }

    data class Number(
        val value: Long,
    ) : AuditValue

    data class Decimal(
        val value: Double,
    ) : AuditValue

    data class Bool(
        val value: Boolean,
    ) : AuditValue

    data class TextList(
        val values: List<String>,
    ) : AuditValue {
        init {
            require(values.isNotEmpty()) { "text list must not be empty." }
            require(values.none { it.isEmpty() }) { "text list values must not contain empty values." }
        }
    }
}

data class AuditTextQuery(
    val query: String,
    val mode: AuditTextMatchMode = AuditTextMatchMode.CONTAINS,
    val caseSensitive: Boolean = false,
) {
    init {
        require(query.isNotBlank()) { "query text must not be blank." }
    }
}

enum class AuditTextMatchMode {
    CONTAINS,
    EXACT,
    PREFIX,
}

data class AuditSort(
    val field: AuditField,
    val direction: AuditSortDirection = AuditSortDirection.DESC,
)

enum class AuditSortDirection {
    ASC,
    DESC,
}
