package io.github.aeshen.observability.query

/**
 * Preferred typed query SPI for backend implementations.
 */
interface AuditSearchQueryService {
    fun search(query: AuditSearchQuery): AuditQueryResult
}
