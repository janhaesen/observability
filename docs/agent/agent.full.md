# Agent Context: observability

Last updated: 2026-03-22
Repository: `observability`
Primary language: Kotlin (JVM, Kotlin 2.3.20)
Build tool: Gradle Kotlin DSL

## Project Purpose

`observability` is an opinionated, production-ready Kotlin framework for structured application observability.

Its goal is to provide a **single, type-safe API** to emit structured events, enrich/process them through a pipeline, and deliver them to one or more sinks (console, SLF4J, file, zip, OpenTelemetry), with optional reliability features and encryption.

The design target is both:
- **Developer ergonomics** (simple `info/warn/error` APIs, event DSL, typed context), and
- **Operational reliability/auditability** (retry, batching, diagnostics hooks, optional strict profile).

## Core Intention / Why This Exists

- Replace ad-hoc logging with a **typed event model**.
- Keep observability output backend-agnostic while supporting concrete integrations.
- Provide extension points (sink SPI, codec SPI) that are stable for third-party modules.
- Support stricter audit use-cases via `AUDIT_DURABLE`.
- Preserve binary compatibility for stable SPI surfaces in patch/minor versions.

## High-Level Architecture

Event flow (from `README.md` and runtime implementation):

1. `ObservabilityEvent` is emitted through `Observability`.
2. `ContextProvider`s merge global context into each event.
3. `ObservabilityCodec` encodes event into bytes (`EncodedEvent`).
4. Metadata enrichers attach runtime metadata.
5. Processors transform bytes (e.g., encryption).
6. Encoded event is fanned out to configured sinks.

Main orchestration:
- `io.github.aeshen.observability.ObservabilityFactory`
- `io.github.aeshen.observability.ObservabilityPipeline`

Behavioral notes:
- `Observability` is `Closeable`; expected usage is `use { ... }` or explicit `close()`.
- Pipeline is thread-safe and guarded by lifecycle lock + open/closed state.
- Sink exceptions (`Exception`) are swallowed by default unless strict mode is enabled.
- Fatal JVM `Error` types are intentionally not swallowed.

## Primary Public Concepts

### Event model
- `EventName` with `resolvedName()` (supports explicit serialized names; enum usage encouraged to avoid cardinality explosion).
- `ObservabilityEvent` with level, message, payload, context, throwable.
- DSL entry point: `event(name) { ... }`.

### Emit API
`Observability` exposes:
- `trace`, `debug`, `info`, `warn`, `error`, and `emit`.

### Context model
- `ObservabilityContext` is type-safe (`TypedKey<T>`).
- Built-in key enums in `key/Keys.kt`:
    - `StringKey`, `LongKey`, `DoubleKey`, `BooleanKey`
- Namespacing/grouping helpers:
    - `NamespacedKey`, `putNamespaced`, `KeyGroup`.

## Built-in Sink Configurations

In `config/sink/`:
- `Console`
- `Slf4j`
- `File`
- `ZipFile`
- `OpenTelemetry`

OpenTelemetry config validates queue/batch/timing constraints and defaults to OTLP HTTP endpoint `http://localhost:4318/v1/logs`.

## Reliability Features

Sink decorators under `sink/decorator/`:
- `AsyncObservabilitySink`
- `BatchingObservabilitySink`
- `RetryingObservabilitySink`
- `BackoffStrategy`

Factory profile:
- `ObservabilityFactory.Profile.STANDARD`
- `ObservabilityFactory.Profile.AUDIT_DURABLE`

`AUDIT_DURABLE` applies (from factory code/docs):
- Retry wrapper (max attempts = 5)
- Batch wrapper (max batch size = 100, flush interval = 250ms)
- `failOnSinkError = true`

## Encryption Support

Config types in `config/encryption/`:
- `AesGcm`
- `RsaKeyWrapped`

Factory helper:
- `Config.aesGcmFromRawKeyBytes(rawKey)` validates AES key length (16/24/32 bytes).

Processor behavior:
- Encryption is applied in processor stage after encoding.

## Diagnostics & Observability of the Framework Itself

`ObservabilityDiagnostics` hooks expose:
- sink handle errors
- sink close errors
- async drop events
- async worker errors
- batch flush outcomes
- retry exhaustion

Default is `NOOP`; users can plug monitoring/metrics adapters.

## Extension / SPI Contract

Stable extension seams (per `docs/spi-contract.md` and `docs/extensions.md`):
- `SinkConfig`
- `ObservabilitySink`
- `SinkProvider`
- `SinkRegistry`
- `ObservabilityDiagnostics`
- `ObservabilitySinkConformanceSuite`
- `AuditSearchQueryService` (in `query-spi`)
- `AuditQueryService` (deprecated compatibility surface in `query-spi`)

Compatibility promise:
- patch/minor releases preserve binary compatibility for stable SPI symbols.
- major versions may introduce breaking changes.

## Query SPI Module

Module: `:query-spi` (optional artifact)

Provides backend-agnostic audit query interfaces:
- `AuditQuery`
- `AuditSearchQuery`
- `AuditRecord`
- `AuditQueryResult`
- `AuditSearchQueryService`
- `AuditQueryService` (deprecated compatibility bridge)

Intended for backend-specific implementations (OpenSearch, ClickHouse, PostgreSQL, etc.).
Module is lightweight and has no heavy runtime dependencies.

## Repository / Module Structure

Configured in `settings.gradle.kts`:
- Root module `:` → core library (`observability`)
- `:query-spi` → audit query SPI
- `:benchmarks` → load/backpressure benchmark harness
- `:examples:third-party-sink-example` → sample external sink/provider + conformance tests

## Tooling & Quality Gates

From build scripts/docs:
- Kotlin JVM `2.3.20`
- Lint/format: ktlint Gradle plugin (`ktlintCheck`, `ktlintFormat`)
- Static analysis: Detekt (`1.23.8`) for non-formatting quality rules
- Binary compatibility validator plugin (`apiCheck` workflow)
- Publishing: Maven publication (`io.github.aeshen:observability:1.0.0`) and GitHub Packages repo config
- Release process documented in `docs/release.md` (tagged `v*` workflow)

## Current Release Status (from repository files)

From `CHANGELOG.md`:
- Stable release `1.0.0` (dated 2026-03-21)
- Includes unified API, pipeline, sinks, decorators, encryption, diagnostics, SPI, query-spi, benchmarks, and example module.

## Operational Guidelines for Future Agent Sessions

1. Treat this as a **structured event framework**, not a plain logging helper.
2. Preserve the event pipeline contract and close semantics.
3. Prefer enum-based `EventName` usage to control cardinality.
4. Keep sink SPI behavior thread-safe and close-safe.
5. When changing SPI symbols, evaluate binary compatibility and docs (`docs/spi-contract.md`).
6. Keep optional integrations (`OpenTelemetry`, `Slf4j`) optional at compile/runtime boundaries.
7. For reliability-sensitive changes, verify behavior under `AUDIT_DURABLE`.
8. Update docs + changelog alongside any user-visible behavior changes.

## Fast File Map (Most Relevant)

- Project overview: `README.md`
- Core factory: `src/main/kotlin/io/github/aeshen/observability/ObservabilityFactory.kt`
- Core pipeline: `src/main/kotlin/io/github/aeshen/observability/ObservabilityPipeline.kt`
- Public API facade: `src/main/kotlin/io/github/aeshen/observability/Observability.kt`
- Event/context model: `src/main/kotlin/io/github/aeshen/observability/ObservabilityEvent.kt`, `src/main/kotlin/io/github/aeshen/observability/ObservabilityContext.kt`
- Sink configs: `src/main/kotlin/io/github/aeshen/observability/config/sink/`
- Encryption configs: `src/main/kotlin/io/github/aeshen/observability/config/encryption/`
- SPI policy: `docs/spi-contract.md`
- Extension guide: `docs/extensions.md`
- Release process: `docs/release.md`
- Query SPI: `query-spi/src/main/kotlin/io/github/aeshen/observability/query/`
