package io.github.aeshen.observability.query

data class AuditQueryResult(
    val records: List<AuditRecord>,
    val total: Long,
)

