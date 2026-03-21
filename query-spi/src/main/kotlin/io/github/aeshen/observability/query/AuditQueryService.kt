package io.github.aeshen.observability.query

interface AuditQueryService {
    fun search(query: AuditQuery): AuditQueryResult
}

