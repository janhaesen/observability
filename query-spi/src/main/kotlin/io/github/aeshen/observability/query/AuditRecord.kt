package io.github.aeshen.observability.query

data class AuditRecord(
    val id: String,
    val timestampEpochMillis: Long,
    val level: String,
    val event: String,
    val message: String?,
    val context: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
)

