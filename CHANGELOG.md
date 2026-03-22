# Changelog

All notable changes to this project are documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## [Unreleased]

### Changed
- Evolved the optional `query-spi` module toward a typed, future-proof contract with `AuditSearchQueryService` and `AuditSearchQuery`, while retaining `AuditQueryService` as a deprecated compatibility bridge.
- Standardized the recommended `query-spi` naming convention for dynamic query fields as `context.<key>` and `metadata.<key>`, with additive `AuditField` helpers for those namespaces.

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
