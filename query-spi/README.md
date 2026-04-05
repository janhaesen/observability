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

## Reference translator kit

`query-spi` ships a backend-neutral translator kit so backend authors can focus on target query syntax instead of hand-rolling model traversal.

Core building blocks:

- `AuditSearchQueryTranslator` to walk `AuditSearchQuery` consistently
- `AuditFieldMapper` to map canonical SPI fields to backend field identifiers
- `AuditPredicateFactory`, `AuditSortFactory`, and `AuditTextFactory` to build backend clauses
- `StandardAuditFieldMappings` and `StandardAuditFieldMapper` to parse/map built-ins plus `context.<key>` and `metadata.<key>`
- `ReferenceBackendTranslator` (demo) showing one complete end-to-end translation path

Example backend-neutral wiring:

```kotlin
import io.github.aeshen.observability.query.AuditComparisonOperator
import io.github.aeshen.observability.query.AuditLogicalOperator
import io.github.aeshen.observability.query.AuditSearchQuery
import io.github.aeshen.observability.query.AuditValue
import io.github.aeshen.observability.query.reference.AuditPredicateFactory
import io.github.aeshen.observability.query.reference.AuditSearchQueryTranslator
import io.github.aeshen.observability.query.reference.AuditSortFactory
import io.github.aeshen.observability.query.reference.AuditTextFactory
import io.github.aeshen.observability.query.reference.StandardAuditFieldMapper

val translator =
    AuditSearchQueryTranslator(
        fieldMapper = StandardAuditFieldMapper(),
        predicateFactory =
            object : AuditPredicateFactory<String, String> {
                override fun comparison(field: String, operator: AuditComparisonOperator, value: AuditValue): String =
                    "$field $operator $value"

                override fun exists(field: String, shouldExist: Boolean): String =
                    if (shouldExist) "$field IS NOT NULL" else "$field IS NULL"

                override fun group(operator: AuditLogicalOperator, criteria: List<String>): String =
                    criteria.joinToString(
                        separator = if (operator == AuditLogicalOperator.AND) " AND " else " OR ",
                        prefix = "(",
                        postfix = ")",
                    )
            },
        sortFactory = AuditSortFactory { field, direction -> "$field ${direction.name}" },
        textFactory = AuditTextFactory { text -> "TEXT(${text.mode},${text.query},${text.caseSensitive})" },
    )

fun toBackendQuery(query: AuditSearchQuery): String {
    val translated = translator.translate(query)
    return listOfNotNull(translated.filter, translated.text).joinToString(" AND ")
}
```

For a full reference translation demo (criteria groups, text semantics, sort mapping, paging), see:
`io.github.aeshen.observability.query.reference.demo.ReferenceBackendTranslator`.

## Cursor-based pagination

By default `AuditSearchQuery` uses offset pagination via the `AuditPage` type. For large datasets
or real-time feeds, cursor-based pagination avoids the row-shift problem and is more efficient on
most backends.

Use `AuditPagination.Cursor` to opt in:

```kotlin
// First page — no cursor yet
val firstPage = AuditSearchQuery(
    fromEpochMillis = 1_710_000_000_000,
    toEpochMillis   = 1_710_003_600_000,
    pagination      = AuditPagination.Offset(limit = 50),
    sort            = listOf(
        AuditSort(AuditField.TIMESTAMP_EPOCH_MILLIS, AuditSortDirection.DESC),
        AuditSort(AuditField.ID, AuditSortDirection.ASC),  // tiebreaker — required for cursor stability
    ),
)
val result1: AuditQueryResult = service.search(firstPage)

// Subsequent pages — pass nextCursor from the previous result
result1.nextCursor?.let { cursor ->
    val nextPage = AuditSearchQuery(
        fromEpochMillis = 1_710_000_000_000,
        toEpochMillis   = 1_710_003_600_000,
        pagination      = AuditPagination.Cursor(limit = 50, after = cursor),
        sort            = firstPage.sort,
    )
    val result2: AuditQueryResult = service.search(nextPage)
}
```

`AuditQueryResult.nextCursor` is `null` when there are no more results or when the backend does
not support cursor pagination.

**Stable sort requirement:** cursor pagination depends on a deterministic result order. Always
include a tie-breaking field such as `AuditField.ID` as the last sort clause. Backends may reject
or silently fall back to offset pagination if no stable sort is present.

**Backend authors:** backends that support cursor pagination should populate
`AuditQueryResult.nextCursor` with an opaque token (e.g. base64-encoded keyset values,
Elasticsearch `search_after` payload) and handle `AuditPagination.Cursor.after` in queries.
Backends that do not support cursors may ignore the `after` field and leave `nextCursor` as `null`.

## Capability negotiation

Not all backends support the same feature set. A backend can declare which capabilities it
supports by implementing the `QueryCapabilityAware` interface alongside `AuditSearchQueryService`.

### Declaring capabilities in a backend

```kotlin
class MyAuditQueryService : AuditSearchQueryService, QueryCapabilityAware {
    override val capabilities = QueryCapabilityDescriptor(
        setOf(
            QueryCapability.OFFSET_PAGINATION,
            QueryCapability.SORT,
            QueryCapability.TEXT_SEARCH,
        )
    )

    override fun search(query: AuditSearchQuery): AuditQueryResult {
        TODO("map query to backend")
    }
}
```

Use the built-in presets when appropriate:

| Preset | Included capabilities |
|---|---|
| `QueryCapabilityDescriptor.FULL` | All known capabilities |
| `QueryCapabilityDescriptor.MINIMAL` | `OFFSET_PAGINATION` only |

### Inspecting capabilities as a consumer

```kotlin
val caps = (queryService as? QueryCapabilityAware)?.capabilities
    ?: QueryCapabilityDescriptor.MINIMAL  // conservative default for non-aware backends
```

### Validating a query before execution

Use `QueryCapabilityValidator` to fail fast when a query requires features the backend has not
declared:

```kotlin
// Returns a list of violations — empty means compatible.
val violations = QueryCapabilityValidator.check(query, caps)

// Throws UnsupportedQueryCapabilityException if any violations are found.
QueryCapabilityValidator.validate(query, caps)
```

`UnsupportedQueryCapabilityException` is a subclass of `IllegalArgumentException` and carries the
full list of `QueryCapabilityViolation` objects for programmatic inspection.

### Available capabilities

| Capability | Triggered when |
|---|---|
| `TEXT_SEARCH` | `query.text` is non-null |
| `SORT` | `query.sort` differs from the default (single timestamp DESC) |
| `NESTED_CRITERIA` | any criterion contains an `AuditCriterion.Group` |
| `OFFSET_PAGINATION` | resolved pagination is `AuditPagination.Offset` |
| `CURSOR_PAGINATION` | resolved pagination is `AuditPagination.Cursor` |
| `PROJECTIONS` | forward-looking: not yet modelled in the shared query contract |

`QueryCapabilityAware` is **opt-in** — existing `AuditSearchQueryService` implementations remain
valid without any changes.

## Query semantics guidance

Recommended semantics for portable behavior across backends:

- time window: always apply `fromEpochMillis <= timestamp <= toEpochMillis`
- top-level `criteria` list: combine with `AND`
- nested `AuditCriterion.Group`: combine according to its `operator`
- `AuditCriterion.Exists(field, true)`: field is present/non-null
- `AuditCriterion.Exists(field, false)`: field is missing/null
- `AuditTextQuery.CONTAINS`: substring match
- `AuditTextQuery.EXACT`: full-string match
- `AuditTextQuery.PREFIX`: starts-with match
- `AuditSort`: apply in declaration order
- `AuditPage`: apply `limit` and `offset` after filtering and sorting

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
