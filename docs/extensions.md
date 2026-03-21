# Sink Extension Guide

This guide defines the extension contract for organizations that add custom sinks.

The formal compatibility policy lives in `docs/spi-contract.md`.

## Stable Extension Seams

- `SinkConfig` is an open interface. Third-party modules can define their own config type.
- `SinkProvider` maps a `SinkConfig` into an `ObservabilitySink`.
- `SinkRegistry` resolves configured sinks through registered providers.
- `ObservabilityFactory.create(sinks = listOf(...))` supports direct sink instances.

## Threading And Lifecycle Guarantees

- `ObservabilitySink.handle` may be called frequently and should be fast and thread-safe.
- `ObservabilitySink.close` is called when `Observability` is closed; implementations should flush and release resources.
- `close()` should be safe to call more than once.
- Sink exceptions are swallowed by default unless `failOnSinkError = true`.

## Error Handling Expectations

- Throw from `handle` only for unrecoverable errors.
- Prefer internal retries/backoff for transient transport failures.
- If your sink buffers asynchronously, make drop/backpressure behavior explicit.

## Optional Reliability Decorators

- `AsyncObservabilitySink` offloads writes to a worker queue.
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

## Register A Provider

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkProvider
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
    SinkRegistry.default().withProvider(
        SinkProvider { cfg ->
            if (cfg is PartnerSinkConfig) PartnerSink() else null
        },
    )

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(PartnerSinkConfig("https://partner.example/logs")),
            sinkRegistry = registry,
        ),
    )
```

## Conformance Testing

Use `ObservabilitySinkConformanceSuite` from test fixtures in your sink module and implement:

- `createSubjectSink()`
- `observedEvents()`

This verifies baseline behavior for event forwarding and close semantics.

