@file:Suppress("unused")

package io.github.aeshen.observability.query

/**
 * Typed, backend-agnostic query contract intended for long-term API stability.
 */
data class AuditSearchQuery(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val page: AuditPage = AuditPage(),
    val criteria: List<AuditCriterion> = emptyList(),
    val text: AuditTextQuery? = null,
    val sort: List<AuditSort> = listOf(AuditSort(field = AuditField.TIMESTAMP_EPOCH_MILLIS)),
) {
    init {
        require(fromEpochMillis >= 0) { "fromEpochMillis must be greater than or equal to 0." }
        require(toEpochMillis >= 0) { "toEpochMillis must be greater than or equal to 0." }
        require(fromEpochMillis <= toEpochMillis) { "fromEpochMillis must be less than or equal to toEpochMillis." }
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
