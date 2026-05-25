# Repository Context: observability

`observability` is a Kotlin framework for emitting typed application events, enriching and processing them through a pipeline, and delivering them to one or more sinks with optional reliability and encryption.

Use this file as the **thin shared entry point** for contributors and agents. It complements the user-facing [`README.md`](./README.md) and the deeper agent references in [`agent.md`](./agent.md) and [`docs/agent/agent.full.md`](./docs/agent/agent.full.md).

## Core mental model

Every emitted event flows through the same stages:

1. `Observability` receives an `ObservabilityEvent`
2. `ContextProvider`s merge ambient context
3. `ObservabilityCodec` encodes the event into an `EncodedEvent`
4. `MetadataEnricher`s attach runtime metadata
5. `ObservabilityProcessor`s transform bytes and metadata
6. configured sinks fan out delivery

Keep changes aligned with that ordering unless you are intentionally changing the public pipeline contract.

## Repository map

| Path | Purpose |
| --- | --- |
| `src/main/kotlin/io/github/aeshen/observability/` | core public API, pipeline, sinks, processors, diagnostics |
| `query-spi/` | optional backend-agnostic audit query SPI |
| `benchmarks/` | comparative performance and backpressure harness |
| `examples/third-party-sink-example/` | reference external sink/provider module with conformance tests |
| `docs/` | architecture notes, extension contracts, release docs, schemas, ADRs |
| `otel/` | local OpenTelemetry collector setup for manual sink verification |
| `api/observability.api` | binary compatibility snapshot used by `apiCheck` |

## High-signal invariants

- Treat this as a structured event framework, not a thin logging wrapper.
- Preserve `Closeable` lifecycle semantics for `Observability` and sinks.
- Keep optional integrations optional at runtime boundaries.
- Treat stable SPI surfaces carefully; see [`docs/spi-contract.md`](./docs/spi-contract.md).
- Validate reliability-sensitive changes against `AUDIT_DURABLE`.

## Language

**Persistent buffering**:
Appending encoded events to a local durable journal before delegated delivery so unacknowledged events can survive process restarts.
_Avoid_: Spool, disk queue, WAL

**Replay**:
Deterministic redelivery of journaled, unacknowledged events in their original sequence when a persistent buffer starts.
_Avoid_: Recovery resend, best-effort resend

**Acknowledged event**:
A journaled event whose delegated delivery completed, allowing the persistent buffer to advance retention and cleanup.
_Avoid_: Flushed event, processed event

### Example dialogue

> **Developer:** If the process crashes after buffering but before the sink confirms delivery, what do we call the next startup behavior?
>
> **Domain expert:** That's a **replay** of the unacknowledged events from the **persistent buffer**.
>
> **Developer:** And once the delegate sink accepts one of those events?
>
> **Domain expert:** It becomes an **acknowledged event**, so the buffer can clean it up.

## Where to look next

- **Using the library:** [`README.md`](./README.md)
- **Contributing and validation commands:** [`CONTRIBUTING.md`](./CONTRIBUTING.md)
- **Documentation map:** [`docs/README.md`](./docs/README.md)
- **Extension contracts:** [`docs/extensions.md`](./docs/extensions.md), [`docs/spi-contract.md`](./docs/spi-contract.md)
- **Machine-readable event contract:** [`docs/event-schema.md`](./docs/event-schema.md), [`docs/schema/README.md`](./docs/schema/README.md)
- **Architecture decisions:** [`docs/adr/`](./docs/adr/)

## Default validation commands

```bash
./gradlew test apiCheck ktlintCheck detekt --no-daemon
./gradlew publish --dry-run --no-daemon
```
