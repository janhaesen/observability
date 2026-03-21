package io.github.aeshen.observability.query

data class AuditQuery(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val limit: Int = 100,
    val offset: Int = 0,
    val filters: Map<String, String> = emptyMap(),
    val freeText: String? = null,
)

