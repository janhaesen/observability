package io.github.aeshen.observability.query

@Suppress("DEPRECATION")
data class AuditQuery(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val limit: Int = 100,
    val offset: Int = 0,
    @Deprecated(
        message = "Use AuditSearchQuery.criteria for typed, extensible filtering.",
        replaceWith = ReplaceWith("toSearchQuery().criteria"),
    )
    val filters: Map<String, String> = emptyMap(),
    @Deprecated(
        message = "Use AuditSearchQuery.text for portable full-text intent.",
        replaceWith = ReplaceWith("toSearchQuery().text"),
    )
    val freeText: String? = null,
) {
    init {
        require(fromEpochMillis >= 0) { "fromEpochMillis must be greater than or equal to 0." }
        require(toEpochMillis >= 0) { "toEpochMillis must be greater than or equal to 0." }
        require(fromEpochMillis <= toEpochMillis) { "fromEpochMillis must be less than or equal to toEpochMillis." }
        require(limit > 0) { "limit must be greater than 0." }
        require(offset >= 0) { "offset must be greater than or equal to 0." }
        require(filters.keys.none { it.isBlank() }) { "filters must not contain blank keys." }
    }

    /**
     * Converts the legacy query shape into the typed, backend-agnostic model.
     */
    fun toSearchQuery(): AuditSearchQuery {
        val mappedCriteria =
            filters.entries.map { (key, value) ->
                AuditCriterion.Comparison(
                    field = AuditField.custom(key),
                    operator = AuditComparisonOperator.EQ,
                    value = AuditValue.Text(value),
                )
            }
        val mappedText = freeText?.trim()?.takeIf { it.isNotEmpty() }?.let(::AuditTextQuery)

        return AuditSearchQuery(
            fromEpochMillis = fromEpochMillis,
            toEpochMillis = toEpochMillis,
            page = AuditPage(limit = limit, offset = offset),
            criteria = mappedCriteria,
            text = mappedText,
        )
    }
}
