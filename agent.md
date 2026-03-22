# Agent Context (Quick): observability

Canonical deep reference: `docs/agent/agent.full.md`  
If this file and the full file diverge, treat `docs/agent/agent.full.md` as source of truth.

## Mission

`observability` is a Kotlin framework for structured application observability:
- emit typed events through one API,
- process them through a configurable pipeline,
- deliver to one or more sinks with optional reliability and encryption.

Primary goals:
- developer ergonomics (simple API + event DSL + typed context),
- production reliability/auditability (retry, batching, diagnostics, strict profile).

## Core Mental Model

Pipeline:
1. `ObservabilityEvent` emitted via `Observability`
2. global context merged by `ContextProvider`
3. event encoded by `ObservabilityCodec`
4. metadata enriched
5. processors applied (e.g., encryption)
6. fan-out to sinks

Key orchestration classes:
- `src/main/kotlin/io/github/aeshen/observability/ObservabilityFactory.kt`
- `src/main/kotlin/io/github/aeshen/observability/ObservabilityPipeline.kt`

## Public API Landmarks

- Emit API: `trace`, `debug`, `info`, `warn`, `error`, `emit`
- Event naming: `EventName` (`resolvedName()`), prefer enums for stable cardinality
- Event DSL: `event(name) { ... }`
- Typed context: `ObservabilityContext`, `TypedKey<T>`, key enums in `key/Keys.kt`

## Sinks, Reliability, Encryption

Built-in sink configs:
- `Console`, `Slf4j`, `File`, `ZipFile`, `OpenTelemetry`

Reliability decorators:
- `AsyncObservabilitySink`
- `BatchingObservabilitySink`
- `RetryingObservabilitySink`
- `BackoffStrategy`

Profiles:
- `STANDARD`
- `AUDIT_DURABLE` (retry + batching + strict sink error propagation)

Encryption configs:
- `AesGcm`
- `RsaKeyWrapped`

## Extension & Compatibility Rules

Stable SPI surfaces are documented in:
- `docs/spi-contract.md`
- `docs/extensions.md`

Do not casually break:
- `SinkConfig`, `ObservabilitySink`, `SinkProvider`, `SinkRegistry`,
- `ObservabilityDiagnostics`,
- `ObservabilitySinkConformanceSuite`,
- `AuditSearchQueryService` (from `:query-spi`),
- `AuditQueryService` (deprecated compatibility surface from `:query-spi`).

Patch/minor versions are expected to keep binary compatibility for stable SPI.

## Module Map

From `settings.gradle.kts`:
- `:` core observability library
- `:query-spi` optional audit query SPI
- `:benchmarks` sink/backpressure benchmark harness
- `:examples:third-party-sink-example` custom sink/provider example + conformance tests

## Working Guardrails

- Treat this as a structured event framework, not just logging wrappers.
- Preserve close/lifecycle semantics (`Observability` is `Closeable`).
- Keep sink behavior thread-safe and close-safe.
- Keep optional integrations optional at runtime boundaries.
- Validate reliability-sensitive changes against `AUDIT_DURABLE`.
- Update docs + changelog for user-visible behavior changes.

## Fast Pointers

- Project overview: `README.md`
- Release process: `docs/release.md`
- Changelog: `CHANGELOG.md`
- Full agent context: `docs/agent/agent.full.md`
