# Sink Extension Guide

This guide defines the extension contract for organizations that add custom sinks.

The formal compatibility policy lives in `docs/spi-contract.md`.

## Stable Extension Seams

- `SinkConfig` is an open interface. Third-party modules can define their own config type.
- `SinkProvider` maps a `SinkConfig` into an `ObservabilitySink`.
- `SinkRegistry` resolves configured sinks through registered providers.
- `ObservabilityFactory.create(ObservabilityFactory.Config(...))` wires sinks through `SinkRegistry`.
- `MetadataEnricher` is a stable extension point for enriching encoded events with runtime metadata.
- `ObservabilityProcessor` is a stable extension point for byte/metadata transformations after enrichment and before sink delivery.

## Metadata Enrichers

Metadata enrichers allow you to attach runtime metadata to events after encoding but before sink delivery. This enables flexible injection of deployment-specific, environment-specific, or correlation metadata without modifying event context or codecs.

### Pipeline Position

Metadata enrichers are applied in the following pipeline position:

```
ObservabilityEvent (emitted)
      │
      ▼
ContextProviders   (merge ambient context into event)
      │
      ▼
Codec              (encode event → EncodedEvent with bytes)
      │
      ▼
MetadataEnrichers  ← You are here (enrich metadata map)
      │
      ▼
Processors         (e.g., encryption)
      │
      ▼
Sinks (fan-out)    (deliver EncodedEvent)
```

### Built-in Enrichers

The library ships with several ready-made enrichers:

- `IngestedAtEnricher`: Adds the `ingestedAt` timestamp (milliseconds since epoch)
- `VersionEnricher`: Adds `version` and `buildSha` fields (e.g., for deployment tracking)
- `EnvironmentEnricher`: Adds `environment` and `region` fields (e.g., "prod", "us-west-2")
- `HostEnricher`: Adds `hostname` and optional `nodeId` (e.g., pod name, instance ID)
- `CorrelationIdEnricher`: Adds `traceId` and `requestId` from supplier functions (e.g., from MDC)

### Custom Enricher Example

```kotlin
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.enricher.MetadataEnricher

class CustomEnricher : MetadataEnricher {
    override fun enrich(encoded: EncodedEvent) {
        // Add arbitrary metadata to the encoded event
        encoded.metadata["customKey"] = "customValue"
        encoded.metadata["processorId"] = ProcessManager.getCurrentProcessId()
    }
}
```

### Configuration

Pass enrichers to `ObservabilityFactory.create()`:

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.enricher.builtin.EnvironmentEnricher
import io.github.aeshen.observability.enricher.builtin.IngestedAtEnricher
import io.github.aeshen.observability.enricher.builtin.VersionEnricher

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(/* ... */),
            metadataEnrichers = listOf(
                IngestedAtEnricher,
                VersionEnricher("1.0.0", "abc123def"),
                EnvironmentEnricher("prod", "us-west-2"),
            ),
        ),
    )
```

Enrichers are applied in the order provided. Choose ordering carefully if enrichers depend on each other's output.

### Thread Safety

`MetadataEnricher.enrich()` is always called under a read lock within the emit cycle and should be fast and thread-safe. If your enricher holds state, ensure it is thread-safe (e.g., using `AtomicReference` or synchronized access).

## Processors

Processors run after encoding and metadata enrichment, but before sink fan-out. They can transform the encoded bytes, `EncodedEvent.original`, and `EncodedEvent.metadata`.

The library now ships with a first-party `SensitiveFieldProcessor` for deterministic masking/removal of fields such as `context.password`, `metadata.apiToken`, `message`, and `payload`.

### Configuration

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.processor.builtin.SensitiveFieldProcessor
import io.github.aeshen.observability.processor.builtin.SensitiveFieldRule

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(/* ... */),
            processors = listOf(
                SensitiveFieldProcessor(
                    rules = listOf(
                        SensitiveFieldRule.remove("context.password"),
                        SensitiveFieldRule.mask("metadata.email", "[EMAIL]"),
                    ),
                ),
            ),
        ),
    )
```

Processors are applied in the order provided. When encryption is enabled, custom processors run before the built-in encryption processor.

## Sinks

### Threading And Lifecycle Guarantees

- `ObservabilitySink.handle` may be called frequently and should be fast and thread-safe.
- `ObservabilitySink.close` is called when `Observability` is closed; implementations should flush and release resources.
- `close()` should be safe to call more than once.
- `IllegalArgumentException` and `IllegalStateException` from sinks are swallowed by default unless `failOnSinkError = true`.
- Other exception types from `handle` are not swallowed by the pipeline.

## Error Handling Expectations

- Throw from `handle` only for unrecoverable exceptions.
- Prefer internal retries/backoff for transient transport failures.
- If your sink buffers asynchronously, make drop/backpressure behavior explicit.
- Fatal JVM `Error` types are never swallowed by the pipeline.
- Use `ObservabilityDiagnostics` to capture handle/close errors, async drops, and batch flush outcomes.

### Optional Reliability Decorators

- `AsyncObservabilitySink` offloads writes to a worker queue and supports deterministic close timeout/policy.
- `BatchingObservabilitySink` buffers events and flushes by size/interval.
- `BatchCapableObservabilitySink` allows optimized batch delivery.
- `RetryingObservabilitySink` retries transient failures using `BackoffStrategy`.

```kotlin
val reliable =
    io.github.aeshen.observability.sink.decorator.RetryingObservabilitySink(
        delegate = mySink,
        maxAttempts = 5,
        backoff = io.github.aeshen.observability.sink.decorator.BackoffStrategy.exponential(),
    )
```

### Register A Provider

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry

data class PartnerSinkConfig(
    val endpoint: String,
) : SinkConfig

class PartnerSink : ObservabilitySink {
    override fun handle(event: io.github.aeshen.observability.codec.EncodedEvent) {
        // send event to partner endpoint
    }
}

val registry =
    SinkRegistry
        .defaultBuilder()
        .register<PartnerSinkConfig> { PartnerSink() }
        .build()

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(PartnerSinkConfig("https://partner.example/logs")),
            sinkRegistry = registry,
        ),
    )
```

### Conformance Testing

Use `ObservabilitySinkConformanceSuite` from test fixtures in your sink module and implement:

- `createSubjectSink()`
- `observedEvents()`

This verifies baseline behavior for event forwarding and close semantics.

