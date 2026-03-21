package io.github.aeshen.observability.query

data class AuditQueryResult(
    val records: List<AuditRecord>,
    val total: Long,
) {
    init {
        require(total >= 0) { "total must be greater than or equal to 0." }
        require(total >= records.size.toLong()) { "total must be greater than or equal to records.size." }
    }
}
