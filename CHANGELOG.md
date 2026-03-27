# Changelog

All notable changes to this project are documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## [1.2.0] - Unreleased

### Added
- Added built-in generic HTTP sink support via `Http` config (`POST`, `PUT`, `PATCH`) for webhook/ingestion endpoint delivery with configurable headers and timeout.

### Changed
- Documented HTTP sink failure semantics: non-2xx responses and transport failures surface as `IllegalStateException`, enabling composition with existing retry/batching/async decorators.

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
