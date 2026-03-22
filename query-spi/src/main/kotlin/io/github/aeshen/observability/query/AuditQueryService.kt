package io.github.aeshen.observability.query

@Deprecated(
    message = "Implement AuditSearchQueryService for the typed, future-proof query SPI.",
    replaceWith = ReplaceWith("AuditSearchQueryService"),
)
interface AuditQueryService {
    fun search(query: AuditQuery): AuditQueryResult
}

/**
 * Preferred typed query SPI for backend implementations.
 */
interface AuditSearchQueryService {
    fun search(query: AuditSearchQuery): AuditQueryResult
}

/**
 * Bridges typed implementations to the legacy SPI without forcing immediate migrations.
 */
@Suppress("DEPRECATION")
fun AuditSearchQueryService.asLegacyService(): AuditQueryService =
    object : AuditQueryService {
        override fun search(query: AuditQuery): AuditQueryResult = search(query.toSearchQuery())
    }
