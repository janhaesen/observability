# Sink SPI Contract

This document defines the compatibility contract for sink extension points.

## Scope And Stability

The following APIs are considered stable SPI in the current major version:

- `io.github.aeshen.observability.config.sink.SinkConfig`
- `io.github.aeshen.observability.sink.ObservabilitySink`
- `io.github.aeshen.observability.sink.registry.SinkProvider`
- `io.github.aeshen.observability.sink.registry.SinkRegistry`
- `io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics`
- `io.github.aeshen.observability.sink.testing.ObservabilitySinkConformanceSuite`
- `io.github.aeshen.observability.query.AuditQueryService` (query-spi module, deprecated compatibility surface)
- `io.github.aeshen.observability.query.AuditSearchQueryService` (query-spi module)
- `io.github.aeshen.observability.query.AuditSearchQuery` and related typed query model

Behavior changes to the above are treated as breaking changes.

## Behavioral Contract

- `handle(event)` may be called concurrently.
- Implementations should be thread-safe or explicitly wrapped (for example with `AsyncObservabilitySink`).
- `close()` must release resources and be safe to call repeatedly.
- If `handle` throws an `Exception`, pipeline behavior is controlled by `failOnSinkError`.
- Fatal JVM `Error` types are never swallowed.

## Error Propagation Modes

- `failOnSinkError = true`: sink exceptions are propagated to the caller.
- `failOnSinkError = false`: sink exceptions are logged and processing continues.

## Audit-Hardening Profile

The `AUDIT_DURABLE` profile enforces strict reliability semantics:

- Automatically wraps sinks with `RetryingObservabilitySink` (5 attempts, exponential backoff)
- Applies `BatchingObservabilitySink` for efficient delivery (100-event batches, 250ms flush)
- Enables `failOnSinkError = true` to surface failures
- All outcomes are reported via `ObservabilityDiagnostics`

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

## Query Service Integration (query-spi)

The optional `query-spi` module enables backend-agnostic audit record retrieval:

- Prefer implementing `AuditSearchQueryService` in backend-specific modules (OpenSearch, ClickHouse, PostgreSQL, etc.)
- Query using a typed contract: time range, paging, sorting, criteria groups, and portable text-search intent
- Use `AuditField` for standard fields or custom vendor fields without coupling the SPI to one storage stack
- Prefer canonical dynamic field prefixes for portable queries: `context.<key>` for `AuditRecord.context` and `metadata.<key>` for `AuditRecord.metadata`
- Continue accepting `AuditQuery` during migration and convert via `AuditQuery.toSearchQuery()`
- `AuditQueryService` remains available for compatibility but is deprecated in favor of `AuditSearchQueryService`
- Surfaces `AuditRecord` with timestamp, event, level, message, context, and metadata

## Recommended Extension Patterns

- Config-driven sink creation: custom `SinkConfig` + `SinkRegistry.builder().register<...> { ... }.build()`.
- Operator diagnostics: implement `ObservabilityDiagnostics` and pass through `ObservabilityFactory.Config`.
- Runtime instance injection: `ObservabilityFactory.create(mySink)`.
- Reliability wrappers: `RetryingObservabilitySink`, `AsyncObservabilitySink`, `BatchingObservabilitySink`.
- Audit queries: implement `AuditSearchQueryService`; expose `AuditQueryService` only as a compatibility adapter when needed.

## Compatibility Process

- Patch/minor releases preserve binary compatibility for SPI symbols above.
- Deprecated query fields (`AuditQuery.filters`, `AuditQuery.freeText`) remain additive compatibility shims until a future major release.
- Major releases may remove deprecated SPI with migration notes.

