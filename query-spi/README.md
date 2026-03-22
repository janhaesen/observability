# query-spi

Lightweight optional SPI for querying audit records.

Prefer implementing `AuditSearchQueryService` in backend-specific modules (for example OpenSearch, ClickHouse, PostgreSQL).

The module now exposes a typed query contract designed to stay stack-agnostic over time:

- `AuditSearchQuery` keeps the stable transport shape: time window, page, sort, typed criteria, optional text query.
- `AuditCriterion` models portable intent instead of backend-specific syntax.
- `AuditField` is string-backed so vendors can extend beyond the built-in fields without forking the SPI.
- `AuditQuery` remains available as a legacy compatibility type and can be converted with `toSearchQuery()`.

## Canonical field naming for dynamic fields

Use the built-in flat field names for core record columns:

- `id`
- `timestampEpochMillis`
- `level`
- `event`
- `message`

For dynamic maps on `AuditRecord`, prefer these portable prefixes across backends:

- `context.<key>` for entries from `AuditRecord.context`
- `metadata.<key>` for entries from `AuditRecord.metadata`

Examples:

- `context.request_id`
- `context.user_id`
- `metadata.ingestedAt`
- `metadata.source`

Use `AuditField.context("...")` and `AuditField.metadata("...")` for the recommended convention.
Keep `AuditField.custom("...")` for vendor-specific fields that are outside the shared portable model.

```kotlin
class OpenSearchAuditQueryService : io.github.aeshen.observability.query.AuditSearchQueryService {
    override fun search(query: io.github.aeshen.observability.query.AuditSearchQuery): io.github.aeshen.observability.query.AuditQueryResult {
        TODO("map query to backend")
    }
}
```

Example typed query:

```kotlin
val query =
    io.github.aeshen.observability.query.AuditSearchQuery(
        fromEpochMillis = 1_710_000_000_000,
        toEpochMillis = 1_710_003_600_000,
        page = io.github.aeshen.observability.query.AuditPage(limit = 100, offset = 0),
        criteria =
            listOf(
                io.github.aeshen.observability.query.AuditCriterion.Comparison(
                    field = io.github.aeshen.observability.query.AuditField.LEVEL,
                    operator = io.github.aeshen.observability.query.AuditComparisonOperator.EQ,
                    value = io.github.aeshen.observability.query.AuditValue.Text("ERROR"),
                ),
                io.github.aeshen.observability.query.AuditCriterion.Comparison(
                    field = io.github.aeshen.observability.query.AuditField.context("request_id"),
                    operator = io.github.aeshen.observability.query.AuditComparisonOperator.EQ,
                    value = io.github.aeshen.observability.query.AuditValue.Text("req-123"),
                ),
                io.github.aeshen.observability.query.AuditCriterion.Exists(
                    field = io.github.aeshen.observability.query.AuditField.metadata("ingestedAt"),
                ),
            ),
        text = io.github.aeshen.observability.query.AuditTextQuery("payment"),
    )
```

Legacy `AuditQuery.filters` can already carry the same canonical keys, for example
`mapOf("context.request_id" to "req-123")`.

If you still expose the old SPI to consumers, bridge a typed implementation back to it.
`AuditQueryService` is deprecated and should be treated as a migration bridge only:

```kotlin
val legacyService: io.github.aeshen.observability.query.AuditQueryService =
    io.github.aeshen.observability.query.asLegacyService(OpenSearchAuditQueryService())
```

