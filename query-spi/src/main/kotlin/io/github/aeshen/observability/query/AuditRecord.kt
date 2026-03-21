package io.github.aeshen.observability.query

data class AuditRecord(
    val id: String,
    val timestampEpochMillis: Long,
    val level: String,
    val event: String,
    val message: String?,
    val context: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank." }
        require(timestampEpochMillis >= 0) { "timestampEpochMillis must be greater than or equal to 0." }
        require(level.isNotBlank()) { "level must not be blank." }
        require(event.isNotBlank()) { "event must not be blank." }
        require(context.keys.none { it.isBlank() }) { "context must not contain blank keys." }
        require(metadata.keys.none { it.isBlank() }) { "metadata must not contain blank keys." }
    }
}
