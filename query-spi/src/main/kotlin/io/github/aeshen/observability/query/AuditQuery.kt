package io.github.aeshen.observability.query

data class AuditQuery(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val limit: Int = 100,
    val offset: Int = 0,
    val filters: Map<String, String> = emptyMap(),
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
}
