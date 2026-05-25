# Sink SPI Contract

This document defines the compatibility contract for sink extension points.

## Scope And Stability

The following sink-extension APIs are considered stable SPI in the current major version:

- `io.github.aeshen.observability.config.sink.SinkConfig`
- `io.github.aeshen.observability.sink.ObservabilitySink`
- `io.github.aeshen.observability.sink.registry.SinkProvider`
- `io.github.aeshen.observability.sink.registry.SinkRegistry`
- `io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics`
- `io.github.aeshen.observability.sink.testing.ObservabilitySinkConformanceSuite`

Behavior changes to the sink-extension symbols above are treated as breaking changes.

For `query-spi`, the stability story is slightly different:

- `io.github.aeshen.observability.query.AuditSearchQueryService` is the stable service entry point.
- `io.github.aeshen.observability.query.AuditQueryService` remains a deprecated compatibility bridge.
- `AuditSearchQuery` and the related typed query model are the preferred contract, but they are Kotlin data classes and builders. Additive evolution can still require recompilation of precompiled Kotlin consumers when generated signatures change.

When `query-spi` evolution has recompilation or migration impact, the release notes and changelog must call it out explicitly.

## Behavioral Contract

- `handle(event)` may be called concurrently.
- Implementations should be thread-safe or explicitly wrapped (for example with `AsyncObservabilitySink`).
- `close()` must release resources and be safe to call repeatedly.
- If `handle` throws `IllegalArgumentException` or `IllegalStateException`, behavior is controlled by `failOnSinkError`.
- Other exception types from `handle` are not swallowed by the pipeline.
- Fatal JVM `Error` types are never swallowed.

## Error Propagation Modes

- `failOnSinkError = true`: handled sink exceptions are propagated to the caller.
- `failOnSinkError = false`: handled sink exceptions are reported via `ObservabilityDiagnostics` and processing continues.

## Audit-Hardening Profile

The `AUDIT_DURABLE` profile enforces strict reliability semantics:

- Automatically wraps sinks with `RetryingObservabilitySink` (5 attempts, exponential backoff)
- Applies `BatchingObservabilitySink` for efficient delivery (100-event batches, 250ms flush)
- Enables `failOnSinkError = true` to surface failures
- All outcomes are reported via `ObservabilityDiagnostics`

`AUDIT_DURABLE` is not crash-safe persistence. If events must survive process restarts, wrap the runtime sink with `PersistentObservabilitySink` explicitly.

Use when strict audit compliance is required:

```kotlin
ObservabilityFactory.create(
    config.copy(
        profile = ObservabilityFactory.Profile.AUDIT_DURABLE,
        diagnostics = myDiagnostics
    )
)
```

## Diagnostics Hooks

`ObservabilityDiagnostics` provides insight into pipeline reliability:

- `onSinkHandleError`: sink errors during emit
- `onSinkCloseError`: resource cleanup failures
- `onAsyncDrop`: events dropped by async queue (capacity exhausted or closed)
- `onAsyncWorkerError`: async worker thread exceptions
- `onBatchFlush`: batch delivery outcomes (size, elapsed, success/error)
- `onRetryExhaustion`: retry limit exceeded with last error

Implement for monitoring, alerting, or metrics collection without side effects.

## Optional Integrations

- `OpenTelemetry` and `Slf4j` sinks rely on optional runtime dependencies in the host application.
- If missing, sink creation fails fast with guidance to add integration dependencies.
- `Http` sink is dependency-free and posts encoded event bytes to arbitrary HTTP/HTTPS endpoints.
- `Http` sink treats non-2xx responses as failures (`IllegalStateException`) so retry decorators can re-attempt delivery.

## Query Service Integration (query-spi)

The optional `query-spi` module enables backend-agnostic audit record retrieval:

- Prefer implementing `AuditSearchQueryService` in backend-specific modules (OpenSearch, ClickHouse, PostgreSQL, etc.)
- Query using a typed contract: time range, paging, sorting, criteria groups, and portable text-search intent
- Use `AuditField` for standard fields or custom vendor fields without coupling the SPI to one storage stack
- Prefer canonical dynamic field prefixes for portable queries: `context.<key>` for `AuditRecord.context` and `metadata.<key>` for `AuditRecord.metadata`
- Continue accepting `AuditQuery` during migration and convert via `AuditQuery.toSearchQuery()`
- `AuditQueryService` remains available for compatibility but is deprecated in favor of `AuditSearchQueryService`
- Surfaces `AuditRecord` with timestamp, event, level, message, context, and metadata
- Use `AuditSearchQueryTranslator` + `StandardAuditFieldMapper` to implement reusable field/criterion/text/sort translation logic
- `ReferenceBackendTranslator` provides a documented end-to-end implementation pattern for third-party backends

### Query Translation Semantics

- Apply time window as inclusive bounds: `fromEpochMillis <= timestamp <= toEpochMillis`
- Treat top-level `AuditSearchQuery.criteria` as logical `AND`
- Evaluate `AuditCriterion.Group` recursively using the group operator (`AND` or `OR`)
- Map `AuditCriterion.Exists(field, true)` to field-present/non-null semantics
- Map `AuditCriterion.Exists(field, false)` to field-missing/null semantics
- Apply `AuditTextQuery.CONTAINS` as substring, `EXACT` as full-string, and `PREFIX` as starts-with
- Preserve `AuditSort` declaration order
- Apply `AuditPage.limit` and `AuditPage.offset` after filtering and sorting

## Recommended Extension Patterns

- Config-driven sink creation: custom `SinkConfig` + `SinkRegistry.builder().register<...> { ... }.build()`.
- Operator diagnostics: implement `ObservabilityDiagnostics` and pass through `ObservabilityFactory.Config`.
- Runtime sink wiring: pass `SinkConfig` entries via `ObservabilityFactory.Config.sinks` and resolve through `SinkRegistry`.
- Legacy compatibility: `ObservabilityFactory.create(vararg sinks, ...)` still exists as a deprecated bridge and is not the recommended SPI wiring path.
- Reliability wrappers: `RetryingObservabilitySink`, `AsyncObservabilitySink`, `BatchingObservabilitySink`, `PersistentObservabilitySink`.
- Audit queries: implement `AuditSearchQueryService`; expose `AuditQueryService` only as a compatibility adapter when needed.

## Compatibility Process

- Patch releases preserve binary compatibility for the stable API and sink SPI symbols above.
- Minor releases preserve binary compatibility for the stable API and sink SPI surfaces above unless an exception is documented in the release notes.
- `query-spi` changes aim to remain source-compatible for normal call sites, but Kotlin consumers may still need recompilation when generated data-class signatures evolve; if that happens, migration notes must be included in the changelog.
- Deprecated query fields (`AuditQuery.filters`, `AuditQuery.freeText`) remain additive compatibility shims until a future major release.
- Major releases may remove deprecated SPI with migration notes.
