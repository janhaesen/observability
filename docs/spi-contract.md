# Sink SPI Contract

This document defines the compatibility contract for sink extension points.

## Scope And Stability

The following APIs are considered stable SPI in the current major version:

- `io.github.aeshen.observability.config.sink.SinkConfig`
- `io.github.aeshen.observability.sink.ObservabilitySink`
- `io.github.aeshen.observability.sink.registry.SinkProvider`
- `io.github.aeshen.observability.sink.registry.SinkRegistry`
- `io.github.aeshen.observability.sink.testing.ObservabilitySinkConformanceSuite`

Behavior changes to the above are treated as breaking changes.

## Behavioral Contract

- `handle(event)` may be called concurrently.
- Implementations should be thread-safe or explicitly wrapped (for example with `AsyncObservabilitySink`).
- `close()` must release resources and be safe to call repeatedly.
- If `handle` throws, pipeline behavior is controlled by `failOnSinkError`.

## Error Propagation Modes

- `failOnSinkError = true`: sink exceptions are propagated to the caller.
- `failOnSinkError = false`: sink exceptions are logged and processing continues.

## Recommended Extension Patterns

- Config-driven sink creation: custom `SinkConfig` + `SinkProvider` + `SinkRegistry.withProvider(...)`.
- Runtime instance injection: `ObservabilityFactory.create(sinks = listOf(mySink))`.
- Reliability wrappers: `RetryingObservabilitySink`, `AsyncObservabilitySink`, `BatchingObservabilitySink`.

## Compatibility Process

- Patch/minor releases preserve binary compatibility for SPI symbols above.
- Major releases may remove deprecated SPI with migration notes.

