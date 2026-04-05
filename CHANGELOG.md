# Changelog

All notable changes to this project are documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## [Unreleased]

### Added
- **Query capability negotiation** in `query-spi` (issue #10):
  - New `QueryCapability` enum declaring all query features a backend may support: `TEXT_SEARCH`, `SORT`, `NESTED_CRITERIA`, `OFFSET_PAGINATION`, `CURSOR_PAGINATION`, `PROJECTIONS`.
  - New `QueryCapabilityDescriptor` — immutable set of supported capabilities with a `supports(capability)` helper and two built-in presets: `FULL` (all capabilities) and `MINIMAL` (offset pagination only).
  - New `QueryCapabilityAware` interface — optional mixin for `AuditSearchQueryService` implementations to expose their `QueryCapabilityDescriptor`. Existing implementations remain valid without changes.
  - New `QueryCapabilityValidator` — utility with two entry points:
    - `check(query, capabilities)` — returns a `List<QueryCapabilityViolation>` (empty means compatible).
    - `validate(query, capabilities)` — throws `UnsupportedQueryCapabilityException` (a subclass of `IllegalArgumentException`) if any violation is found.
  - New `QueryCapabilityViolation` — structured violation value carrying the offending `QueryCapability` and a human-readable `detail` message.
  - New `UnsupportedQueryCapabilityException` — exposes the full `violations` list for programmatic inspection.
- **Cursor-based pagination** in `query-spi` (issue #9):
  - New `AuditPagination` sealed type with two subtypes:
    - `AuditPagination.Offset(limit, offset)` — traditional limit/offset pagination, equivalent to the existing `AuditPage` semantics.
    - `AuditPagination.Cursor(after, limit)` — cursor-based (keyset) pagination for large datasets and real-time feeds, avoiding row-shift inconsistency.
  - `AuditSearchQuery.pagination: AuditPagination?` — new optional field that takes precedence over the deprecated `page` field when set.
  - `AuditSearchQuery.resolvedPagination: AuditPagination` — computed property returning the effective pagination strategy regardless of which field was set.
  - `AuditSearchQuery.Builder` — fluent Java-friendly builder (`AuditSearchQuery.builder(from, to).pagination(...).build()`).
  - `AuditQueryResult.nextCursor: String?` — opaque continuation token returned by cursor-capable backends; `null` when there are no more results or the backend does not support cursors.
  - `TranslatedAuditQuery.pagination: AuditPagination` replaces `page: AuditPage` in the reference translator kit output.
  - `ReferenceBackendQuery.cursorAfter: String?` — non-null when cursor pagination is active; `offset` is set to `0` in that case.

### Deprecated
- `AuditPage` — use `AuditPagination.Offset` instead.
- `AuditSearchQuery.page` — use `AuditSearchQuery.pagination` or `resolvedPagination` instead.
- `TranslatedAuditQuery.page` — use `TranslatedAuditQuery.pagination` instead.

### Breaking changes (`query-spi`)
These are binary-incompatible changes inherent to evolving Kotlin data classes. Source-level
compatibility is preserved for all existing call sites that use named parameters or omit trailing
default arguments. Any module compiled against the previous `query-spi` artifact must be
recompiled.

- **`AuditQueryResult`** — `copy()` signature changed from `copy(records, total)` to
  `copy(records, total, nextCursor)`. The two-argument Java constructor `new AuditQueryResult(records, total)`
  is preserved via `@JvmOverloads`, but compiled Kotlin call sites that used `.copy(...)` must be
  recompiled.
- **`AuditSearchQuery`** — primary constructor gains a trailing `pagination: AuditPagination?`
  parameter. The Kotlin synthetic default constructor changes accordingly. Existing Kotlin source
  code (`AuditSearchQuery(from, to)`, `AuditSearchQuery(from, to, criteria = ...)`) recompiles
  without modification, but code compiled against the previous binary must be recompiled.
- **`TranslatedAuditQuery`** — the `page: AuditPage` constructor parameter is replaced by
  `pagination: AuditPagination`. Code that constructed `TranslatedAuditQuery` directly
  (uncommon — the type is normally produced by `AuditSearchQueryTranslator`) must be updated.
  A backward-compatible `page` accessor is provided as a deprecated computed property.

## [1.2.0] - Unreleased

### Added
- Added built-in generic HTTP sink support via `Http` config (`POST`, `PUT`, `PATCH`) for webhook/ingestion endpoint delivery with configurable headers and timeout.
- Expanded contribution guidance to cover the broader multi-module surface (`observability`, `query-spi`, `benchmarks`, and `examples`) through a richer PR request template.
- **Java interoperability** improvements across the public API (all additive):
  - `@JvmStatic` on `ObservabilityContext.builder()` / `empty()`, `BackoffStrategy.fixed()` / `exponential()`, `SinkRegistry.builder()` / `defaultBuilder()` / `empty()` / `getDefault()`, `ObservabilityFactory.create()`, and `Config.aesGcmFromRawKeyBytes()` — eliminates `.Companion.` / `.INSTANCE.` noise from Java call sites.
  - `@JvmField` on `ObservabilityDiagnostics.NOOP` — exposed as a direct static field.
  - `@JvmOverloads` on `AsyncObservabilitySink`, `RetryingObservabilitySink`, and `BatchingObservabilitySink` constructors, the `Http` data class constructor, and `BackoffStrategy.exponential()` — Java callers can now omit trailing optional parameters.
  - `ObservabilityFactory.Config.Builder` — new fluent Java builder for the 10-field `Config` data class.
  - `SinkRegistry.Builder.register(Class<T>, Function<T, ObservabilitySink>)` — non-`reified` overload callable from Java (the existing `inline reified` overload is invisible to Java).
  - Explicit single- and two-arg default methods on the `Observability` interface (`info(name)`, `info(name, msg)`, `error(name, msg, throwable)`, and equivalents for all levels) — Kotlin's `$default` stubs do not support partial argument application from Java.
  - `ObservabilityEvent.getJavaTimestamp()` returning `java.time.Instant` and `EventBuilder.timestamp(java.time.Instant)` — bridges `kotlin.time.Instant` to the standard Java time API.
  - `@file:JvmName` renames: `ObservabilityEventKt` → `ObservabilityEvents`, `KeyGroupKt` → `KeyGroups`, `NamespacedKeyKt` → `NamespacedKeys`.
  - `JavaInteropTest.java` test suite with 15 tests covering all of the above improvements.

### Changed
- Documented HTTP sink failure semantics: non-2xx responses and transport failures surface as `IllegalStateException`, enabling composition with existing retry/batching/async decorators.
- Clarified release notes expectations so user-visible changes are tracked consistently across core APIs, SPI contracts, module docs, and integration examples.

### Migration notes (Java callers only)
- `ObservabilityDiagnostics.Companion.getNOOP()` has been replaced by the static field `ObservabilityDiagnostics.NOOP`. Update Java call sites accordingly.
- The auto-generated classes `ObservabilityEventKt`, `KeyGroupKt`, and `NamespacedKeyKt` no longer exist; use `ObservabilityEvents`, `KeyGroups`, and `NamespacedKeys` respectively. Kotlin callers are unaffected.

## [1.1.0] - 2026-03-27

### Added
- Exposed `MetadataEnricher` as a public, first-class configuration extension point via `metadataEnrichers` parameter in `ObservabilityFactory.Config` and overload.
- Added `InMemoryOperationalDiagnostics` as a first-party, lightweight collector for runtime reliability metrics and health snapshots.
- Added first-party sensitive-field filtering via `SensitiveFieldProcessor`, ordered allow/mask/remove rules, and `SensitiveFieldPresets.commonSecrets()` for common secret/token/email-style field names.
- Added built-in metadata enrichers:
  - `IngestedAtEnricher`: adds `ingestedAt` timestamp (milliseconds since epoch)
  - `VersionEnricher`: adds `version` and `buildSha` for deployment tracking
  - `EnvironmentEnricher`: adds `environment` and `region` for environment-specific metadata
  - `HostEnricher`: adds `hostname` and optional `nodeId` for multi-instance deployments
  - `CorrelationIdEnricher`: adds `traceId` and `requestId` from supplier functions for distributed tracing
- Added async diagnostics hooks for queue depth and worker state (`onAsyncQueueDepth`, `onAsyncWorkerState`) to support health/readiness reporting.
- Added a backend-neutral `query-spi` translator kit with `AuditSearchQueryTranslator`, `AuditFieldMapper`, `AuditPredicateFactory`, `AuditSortFactory`, `AuditTextFactory`, and `TranslatedAuditQuery` to support reusable backend query translation.
- Added standard query field mapping helpers in `query-spi`: `StandardAuditFieldMappings`, `StandardAuditField`, and `StandardAuditFieldMapper` for canonical built-ins plus `context.<key>` and `metadata.<key>` prefixes.
- Added `ReferenceBackendTranslator` and `ReferenceBackendQuery` as a documented end-to-end translation example for `AuditSearchQuery` (criteria groups, text query semantics, sort mapping, and pagination).
- Added translator-kit contract tests in `query-spi` covering canonical field resolution, translator behavior, and end-to-end reference translation output.

### Changed
- Moved the `MetadataEnricher` contract to `io.github.aeshen.observability.enricher` and grouped library-provided enrichers under `io.github.aeshen.observability.enricher.builtin` for clearer SPI-versus-builtins separation.
- Evolved the optional `query-spi` module toward a typed, future-proof contract with `AuditSearchQueryService` and `AuditSearchQuery`, while retaining `AuditQueryService` as a deprecated compatibility bridge.
- Standardized the recommended `query-spi` naming convention for dynamic query fields as `context.<key>` and `metadata.<key>`, with additive `AuditField` helpers for those namespaces.
- Added first-class `processors` support to `ObservabilityFactory.Config` while ensuring custom processors run before encryption.
- Standardized sink creation guidance around `ObservabilityFactory.Config.sinks` + `SinkRegistry`; kept direct sink injection as a deprecated compatibility bridge.
- Expanded `query-spi` documentation (`query-spi/README.md`, `README.md`, `docs/spi-contract.md`) with concrete implementation guidance and explicit query translation semantics for third-party backend authors.

## [1.0.0] - 2026-03-21

### Added
- Initial stable release `1.0.0` of the core `observability` library.
- Unified event API with `trace`, `debug`, `info`, `warn`, `error`, and direct `emit` support.
- Event DSL (`event { ... }`) with support for message, level, typed context, payload, and throwable.
- Type-safe context model (`ObservabilityContext`, `TypedKey<T>`, built-in key sets, `KeyGroup`, namespaced key helpers).
- Extensible processing pipeline with context providers, codec abstraction, metadata enrichers, processors, and sink fan-out.
- Built-in sink configurations and implementations: `Console`, `Slf4j`, `File`, `ZipFile`, and `OpenTelemetry` (OTLP HTTP).
- Reliability decorators: `AsyncObservabilitySink`, `BatchingObservabilitySink`, `RetryingObservabilitySink`, and `BackoffStrategy`.
- `AUDIT_DURABLE` profile for stricter delivery semantics (retry + batching + strict sink failure mode).
- Pluggable sink SPI (`SinkConfig`, `SinkProvider`, `SinkRegistry`) for third-party sink integrations.
- Pluggable codec SPI with default JSONL codec.
- Optional event encryption processors: AES-GCM and RSA-wrapped per-event data key mode.
- Runtime diagnostics hooks via `ObservabilityDiagnostics` for sink, async, batch, and retry outcomes.
- Optional `query-spi` module with `AuditQueryService`, `AuditQuery`, `AuditRecord`, and `AuditQueryResult` models.
- Sink conformance test fixtures (`ObservabilitySinkConformanceSuite`) to validate third-party sink behavior.
- Benchmarks module and third-party sink example module.
- CI quality gates for tests, static analysis (`detekt`), binary API checks (`apiCheck`), CVE scanning, and publish dry-runs.
- Release workflow for tagged versions with generated GitHub release notes.
