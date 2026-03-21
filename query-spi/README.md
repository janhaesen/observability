# query-spi

Lightweight optional SPI for querying audit records.

Implement `AuditQueryService` in backend-specific modules (for example OpenSearch, ClickHouse, PostgreSQL).

```kotlin
class OpenSearchAuditQueryService : io.github.aeshen.observability.query.AuditQueryService {
    override fun search(query: io.github.aeshen.observability.query.AuditQuery): io.github.aeshen.observability.query.AuditQueryResult {
        TODO("map query to backend")
    }
}
```

