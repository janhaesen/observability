# observability

An opinionated, production-ready Kotlin framework for structured application observability.

Provides a single, type-safe entry point to emit structured events with typed context metadata and route them through a processing pipeline to one or more configurable sinks (console, SLF4J, file, zip, OpenTelemetry), with optional encryption, async delivery, batching, and retry.

## Table of Contents

- [Architecture](#architecture)
- [Features](#features)
- [Install](#install)
- [Quick Start](#quick-start)
- [Defining Event Names](#defining-event-names)
- [Emitting Events](#emitting-events)
- [Event DSL](#event-dsl)
- [Binary Payloads](#binary-payloads)
- [Event Schema Contract](#event-schema-contract)
- [Type-safe Context](#type-safe-context)
  - [Built-in Keys](#built-in-keys)
  - [Namespaced Keys](#namespaced-keys)
  - [KeyGroup](#keygroup)
  - [Context Builder Utilities](#context-builder-utilities)
- [Global Context with ContextProvider](#global-context-with-contextprovider)
- [Configure Sinks](#configure-sinks)
- [Reliability Decorators](#reliability-decorators)
  - [AsyncObservabilitySink](#asyncobservabilitysink)
  - [BatchingObservabilitySink](#batchingobservabilitysink)
  - [RetryingObservabilitySink](#retryingobservabilitysink)
- [Profiles](#profiles)
- [Configure Encryption](#configure-encryption)
  - [AES-GCM with a fixed key](#aes-gcm-with-a-fixed-key)
  - [RSA-wrapped per-event data key](#rsa-wrapped-per-event-data-key)
- [Sensitive Field Processors](#sensitive-field-processors)
- [Custom Codec](#custom-codec)
- [Extend with Custom Sinks](#extend-with-custom-sinks)
- [Diagnostics Hooks](#diagnostics-hooks)
- [Built-in Operational Diagnostics](#built-in-operational-diagnostics)
- [Java Interoperability](#java-interoperability)
- [Query SPI](#query-spi)
- [OpenTelemetry Setup](#opentelemetry-setup)
- [Conformance Testing](#conformance-testing)
- [Benchmarks](#benchmarks)
- [Run the Test Suite](#run-the-test-suite)
- [Code Quality](#code-quality)
- [Module Structure](#module-structure)
- [Notes](#notes)

---

## Architecture

The pipeline processes each emitted event in the following order:

```
ObservabilityEvent
      │
      ▼
ContextProviders     (merge global context into each event)
      │
      ▼
Codec                (encode event → EncodedEvent with byte[] payload)
      │
      ▼
MetadataEnrichers    (attach runtime metadata, e.g. ingestedAt)
      │
      ▼
Processors           (transform bytes, e.g. encrypt)
      │
      ▼
Sinks (fan-out)      (write to Console, File, OpenTelemetry, …)
```

`ObservabilityPipeline` is thread-safe and `Closeable`. Call `close()` or use `use { }` to flush and release sink resources deterministically.

---

## Features

- **Unified event API** — `trace`, `debug`, `info`, `warn`, `error`, and `emit`
- **Type-safe context** — typed keys (`StringKey`, `LongKey`, `DoubleKey`, `BooleanKey`) and namespaced key grouping
- **Multiple sinks with fan-out** — Console, SLF4J, File (JSONL), ZipFile, OpenTelemetry OTLP
- **Optional encryption** — AES-GCM with a fixed key, or per-event data key wrapped with RSA-OAEP-256
- **Sensitive-field filtering** — ordered allow/mask/remove processors for `context.*`, `metadata.*`, `message`, and `payload`
- **Reliability decorators** — `AsyncObservabilitySink`, `BatchingObservabilitySink`, `RetryingObservabilitySink`
- **Profiles** — `STANDARD` (best-effort) or `AUDIT_DURABLE` (strict, retried, batched)
- **Pluggable codec** — default JSONL, fully replaceable
- **Sink SPI** — register custom sinks via `SinkConfig` + `SinkRegistry`
- **Global context injection** — `ContextProvider` merges ambient context into every event
- **Binary payload support** — attach opaque bytes to any event
- **Diagnostics hooks** — `ObservabilityDiagnostics` for drops, retries, batch flushes, queue depth, worker health, and sink errors
- **Built-in operational collector** — `InMemoryOperationalDiagnostics` provides lightweight counters and health snapshots
- **Binary compatibility tracking** — enforced via `binary-compatibility-validator`
- **Audit query SPI** — `query-spi` module for backend-agnostic retrieval of audit records
- **Java-friendly API** — `@JvmStatic`, `@JvmOverloads`, `Config.Builder`, and explicit overloads make the full API ergonomic from Java

---

## Install

Coordinates:

| Property   | Value                |
|------------|----------------------|
| `group`    | `io.github.aeshen`   |
| `artifact` | `observability`      |
| `version`  | `1.0.0`              |

```kotlin
dependencies {
    implementation("io.github.aeshen:observability:1.0.0")

    // Required only when using the OpenTelemetry sink
    implementation("io.opentelemetry:opentelemetry-api:1.49.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.49.0")

    // Required only when using the SLF4J sink
    implementation("org.slf4j:slf4j-api:2.0.17")
}
```

`opentelemetry-*` and `slf4j-api` are `compileOnly` in the library. If you configure those sinks without the runtime JARs on the classpath, the factory throws a descriptive `IllegalStateException` at startup.

---

## Quick Start

```kotlin
import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.key.LongKey
import io.github.aeshen.observability.key.StringKey

enum class AppEvent(
    override val eventName: String? = null,
) : EventName {
    APP_START("app.start"),
    REQUEST_DONE("request.done"),
}

fun main() {
    val observability =
        ObservabilityFactory.create(
            ObservabilityFactory.Config(
                sinks = listOf(Console),
            ),
        )

    observability.use {
        it.info(
            name = AppEvent.APP_START,
            message = "Application started",
        )

        val context =
            ObservabilityContext
                .builder()
                .put(StringKey.REQUEST_ID, "req-123")
                .put(StringKey.PATH, "/health")
                .put(LongKey.STATUS_CODE, 200L)
                .build()

        it.info(
            name = AppEvent.REQUEST_DONE,
            message = "Request completed",
            context = context,
        )
    }
}
```

---

## Defining Event Names

Implement `EventName` to enumerate the events your service emits. Using an `enum` is the recommended pattern — it prevents cardinality explosion in metrics backends (OpenTelemetry, Elasticsearch, etc.).

```kotlin
enum class AppEvent(
    override val eventName: String? = null,
) : EventName {
    APP_START("app.start"),   // resolves to "app.start"
    UNKNOWN,                  // resolves to enum constant name "UNKNOWN"
}
```

- `eventName` overrides the serialized name. Leave it `null` to fall back to the enum constant name.
- `resolvedName()` returns `eventName` when non-blank, otherwise `name`.

---

## Emitting Events

The `Observability` interface provides convenience methods for each level:

```kotlin
observability.trace(AppEvent.APP_START)
observability.debug(AppEvent.APP_START, message = "debug detail")
observability.info(AppEvent.REQUEST_DONE, message = "ok", context = ctx)
observability.warn(AppEvent.REQUEST_DONE, message = "slow", throwable = e)
observability.error(AppEvent.REQUEST_DONE, message = "failed", throwable = e)

// Emit a pre-built event directly
observability.emit(myEvent)
```

All methods accept an optional `context` parameter (`ObservabilityContext.empty()` by default).
`warn` and `error` also accept an optional `throwable` parameter.

---

## Event DSL

Build events imperatively using the fluent `EventBuilder`:

```kotlin
import io.github.aeshen.observability.event
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel

val e =
    event(AppEvent.REQUEST_DONE) {
        level(EventLevel.INFO)
        message("Request completed")
        context(StringKey.REQUEST_ID, "req-123")
        context(StringKey.PATH, "/orders")
        error(someThrowable)
    }

observability.emit(e)
```

The builder also accepts a full `ObservabilityContext` via `context(other: ObservabilityContext)`.

---

## Binary Payloads

Attach arbitrary binary data to any event using the DSL:

```kotlin
val e =
    event(AppEvent.REQUEST_DONE) {
        level(EventLevel.INFO)
        payload(myByteArray)
    }
```

The default JSONL codec Base64-encodes the payload into `payloadBase64` and includes
`payloadPresent` so consumers can distinguish `null` payloads from empty byte arrays.

---

## Type-safe Context

`ObservabilityContext` is a type-safe container backed by `TypedKey<T> → Any`. Keys are typed at the call site — no stringly-typed maps.

```kotlin
val context =
    ObservabilityContext
        .builder()
        .put(StringKey.REQUEST_ID, "req-123")
        .put(LongKey.STATUS_CODE, 200L)
        .put(DoubleKey.BYTES, 1024.0)
        .put(BooleanKey.SUCCESS, true)
        .build()
```

### Built-in Keys

| Enum         | Key constant  | Type      | JSON field name |
|--------------|---------------|-----------|-----------------|
| `StringKey`  | `NAME`        | `String`  | `name`          |
| `StringKey`  | `USER_AGENT`  | `String`  | `user_agent`    |
| `StringKey`  | `REQUEST_ID`  | `String`  | `id`            |
| `StringKey`  | `PATH`        | `String`  | `path`          |
| `StringKey`  | `METHOD`      | `String`  | `method`        |
| `LongKey`    | `MS`          | `Long`    | `ms`            |
| `LongKey`    | `STATUS_CODE` | `Long`    | `status_code`   |
| `DoubleKey`  | `BYTES`       | `Double`  | `bytes`         |
| `BooleanKey` | `SUCCESS`     | `Boolean` | `success`       |

Define your own keys by implementing `TypedKey<T>`:

```kotlin
enum class MyKey(override val keyName: String) : TypedKey<String> {
    TENANT_ID("tenant_id"),
    TRACE_ID("trace_id"),
}
```

### Namespaced Keys

Prefix keys with a namespace to avoid collisions across subsystems:

```kotlin
import io.github.aeshen.observability.key.putNamespaced

val context =
    ObservabilityContext
        .builder()
        .putNamespaced("request", StringKey.PATH, "/orders")
        .putNamespaced("request", StringKey.METHOD, "GET")
        .putNamespaced("response", LongKey.STATUS_CODE, 200L)
        .build()
// Produces keys: "request.path", "request.method", "response.status_code"
```

### KeyGroup

`KeyGroup` is a `fun interface` for composable, reusable context bundles:

```kotlin
import io.github.aeshen.observability.key.KeyGroup
import io.github.aeshen.observability.key.put
import io.github.aeshen.observability.key.toContext

val requestGroup = KeyGroup { builder ->
    builder.put(StringKey.REQUEST_ID, "req-123")
    builder.put(StringKey.PATH, "/orders")
}

// Use directly in a builder
val context = ObservabilityContext.builder().put(requestGroup).build()

// Or convert to a standalone context
val standalone = requestGroup.toContext()
```

### Context Builder Utilities

```kotlin
// Only insert the key if the value is non-null
builder.putIfNotNull(StringKey.REQUEST_ID, maybeId)

// Merge another context
builder.putAll(existingContext)
```

---

## Global Context with ContextProvider

`ContextProvider` is a `fun interface` that supplies context merged into **every** emitted event automatically:

```kotlin
import io.github.aeshen.observability.ContextProvider

val actorProvider =
    ContextProvider {
        ObservabilityContext
            .builder()
            .put(StringKey.USER_AGENT, currentUser())
            .build()
    }

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(Console),
            contextProviders = listOf(actorProvider),
        ),
    )
```

Multiple providers are applied in order; later providers can overwrite earlier keys.

---

## Metadata Enrichers

`MetadataEnricher` is a `fun interface` that enriches the encoded event's metadata map after encoding but before encryption and sink delivery. Use enrichers to inject runtime metadata such as ingestion timestamp, version, environment, or correlation identifiers without modifying event context or codecs.

### Built-in Enrichers

The library ships with several ready-made enrichers:

```kotlin
import io.github.aeshen.observability.enricher.builtin.CorrelationIdEnricher
import io.github.aeshen.observability.enricher.builtin.EnvironmentEnricher
import io.github.aeshen.observability.enricher.builtin.HostEnricher
import io.github.aeshen.observability.enricher.builtin.IngestedAtEnricher
import io.github.aeshen.observability.enricher.builtin.VersionEnricher

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(Console),
            metadataEnrichers = listOf(
                IngestedAtEnricher,  // adds "ingestedAt" timestamp
                VersionEnricher("1.0.0", "abc123"),  // adds "version" and "buildSha"
                EnvironmentEnricher("prod", "us-west-2"),  // adds "environment" and "region"
                HostEnricher("pod-123", "node-456"),  // adds "hostname" and "nodeId"
                CorrelationIdEnricher(
                    traceIdSupplier = { MDC.get("traceId") },
                    requestIdSupplier = { MDC.get("requestId") },
                ),  // adds "traceId" and "requestId" from suppliers
            ),
        ),
    )
```

| Enricher                 | Metadata fields added | Use case |
|--------------------------|------------------------|----------|
| `IngestedAtEnricher`     | `ingestedAt` (ms)     | Track event ingestion time |
| `VersionEnricher`        | `version`, `buildSha` | Deployment tracking |
| `EnvironmentEnricher`    | `environment`, `region` | Environment/region filtering |
| `HostEnricher`           | `hostname`, `nodeId` (optional) | Multi-instance debugging |
| `CorrelationIdEnricher`  | `traceId`, `requestId` (conditional) | Distributed tracing correlation |

### Custom Enricher

Implement `MetadataEnricher` to add your own metadata:

```kotlin
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.enricher.MetadataEnricher

class RequestCounterEnricher : MetadataEnricher {
    private val counter = AtomicLong(0)

    override fun enrich(encoded: EncodedEvent) {
        encoded.metadata["requestNumber"] = counter.incrementAndGet()
    }
}
```

Enrichers are applied in order; choose ordering carefully if enrichers depend on each other's output.

---

## Configure Sinks

```kotlin
import io.github.aeshen.observability.config.sink.*
import java.nio.file.Path

val config =
    ObservabilityFactory.Config(
        sinks = listOf(
            Console,
            Slf4j(MyService::class),
            File(Path.of("./logs/events.jsonl")),
            ZipFile(Path.of("./logs/events.zip")),
            Http(
                endpoint = "https://collector.example.com/ingest",
                method = HttpMethod.POST,
                headers = mapOf("Content-Type" to "application/x-ndjson"),
                timeoutMillis = 5_000,
            ),
            OpenTelemetry(
                endpoint = "http://localhost:4318/v1/logs",
                serviceName = "my-service",
                serviceVersion = "1.0.0",
                scheduleDelayMillis = 200,
                exporterTimeoutMillis = 30_000,
                maxQueueSize = 2_048,
                maxExportBatchSize = 512,
                headers = mapOf("Authorization" to "Bearer token"),
            ),
        ),
        failOnSinkError = false, // best-effort (default)
    )
```

| Sink config      | Description                                               | Optional runtime dependency                       |
|------------------|-----------------------------------------------------------|---------------------------------------------------|
| `Console`        | Writes encoded event payload directly to `stdout`         | None                                              |
| `Slf4j`          | Logs encoded payload through SLF4J at mapped levels       | `org.slf4j:slf4j-api`                             |
| `File`           | Appends JSONL to a buffered file writer; creates parent dirs if needed | None                                              |
| `ZipFile`        | Appends JSONL entries to a ZIP archive, preserving existing entries via startup replay | None                                              |
| `Http`           | Sends encoded payload bytes to an arbitrary HTTP/HTTPS endpoint (`POST`, `PUT`, `PATCH`) | None                                              |
| `OpenTelemetry`  | Exports via OTLP HTTP to any OTel-compatible backend      | `opentelemetry-api`, `-sdk`, `-exporter-otlp`     |

All sinks receive fan-out delivery. `IllegalArgumentException` and `IllegalStateException` from one sink do not block others (unless `failOnSinkError = true`).

`Http` sink behavior:

- `timeoutMillis` applies to request connect/send timing.
- Any non-2xx response throws `IllegalStateException` (so `RetryingObservabilitySink` can retry).
- Transport errors (`IOException`, `InterruptedException`) are surfaced as `IllegalStateException`.

### Default JSONL Format

Each event is written as a single JSON line with these fields:

```json
{
  "schemaVersion": "1",
  "eventId": "dc7fc4d6-f5e3-4ca0-a0da-0f258f5f79ac",
  "correlationId": "req-123",
  "name": "request.done",
  "level": "INFO",
  "timestamp": "2026-03-21T10:00:00Z",
  "message": "Request completed",
  "context": {"id": "req-123", "status_code": 200},
  "payloadPresent": false,
  "payloadBase64": ""
}
```

- `schemaVersion` is the versioned envelope contract for encoded events.
- `eventId` is generated per encoded event.
- `correlationId` mirrors `context.id` when present, otherwise `null`.
- Numeric and boolean context values are encoded as JSON primitives.

When a `throwable` is attached:

```json
{
  "...",
  "error": {
    "type": "java.lang.RuntimeException",
    "message": "oops",
    "stacktrace": "..."
  }
}
```

## Event Schema Contract

The default `JsonLineCodec` emits a versioned envelope (`schemaVersion = "1"`) with stable core fields:

Canonical references: `docs/event-schema.md` and `docs/schema/event-envelope-v1.schema.json`.

- Required: `schemaVersion`, `eventId`, `name`, `level`, `timestamp`, `message`, `context`, `payloadPresent`, `payloadBase64`
- Optional: `correlationId` (nullable), `error`

Compatibility policy:

- Additive fields may be introduced within schema version `1`.
- Breaking shape changes require a new `schemaVersion`.
- Consumers should branch on `schemaVersion` before strict decoding.

---

## Reliability Decorators

Decorators wrap any `ObservabilitySink` to add async delivery, batching, or retry. They can be composed and are independent of pipeline configuration.

### AsyncObservabilitySink

Offloads writes to a single background worker thread via a bounded queue:

```kotlin
import io.github.aeshen.observability.sink.decorator.AsyncObservabilitySink

val asyncSink =
    AsyncObservabilitySink(
        delegate = mySink,
        capacity = 1024,              // queue depth (default: 1024)
        offerTimeoutMillis = 50,      // offer timeout before drop (default: 50ms)
        failOnDrop = false,           // throw on queue-full drop (default: false)
        closeTimeoutMillis = 5000,    // max time to drain on close (default: 5s)
        shutdownPolicy = AsyncObservabilitySink.ShutdownPolicy.DRAIN, // or DROP_PENDING
        diagnostics = myDiagnostics,
    )
```

If the async worker encounters a delegate failure, the sink reports it via diagnostics and fails subsequent `handle` calls and `close()` deterministically.

**Drop reasons** reported via `ObservabilityDiagnostics.onAsyncDrop`:

| Reason                    | Cause                                                          |
|---------------------------|----------------------------------------------------------------|
| `QUEUE_FULL`              | Queue capacity exhausted within `offerTimeoutMillis`           |
| `CLOSED`                  | Sink already closed when `handle` was called                   |
| `DROP_PENDING_ON_CLOSE`   | `DROP_PENDING` shutdown policy discarded buffered events       |

### BatchingObservabilitySink

Buffers events and flushes by size or time interval. If the delegate implements `BatchCapableObservabilitySink`, flush calls `handleBatch(List<EncodedEvent>)` for optimized delivery:

```kotlin
import io.github.aeshen.observability.sink.decorator.BatchingObservabilitySink

val batchedSink =
    BatchingObservabilitySink(
        delegate = mySink,
        maxBatchSize = 50,           // flush at this many events (default: 50)
        flushIntervalMillis = 1000,  // flush every N ms regardless of size (default: 1s)
        diagnostics = myDiagnostics,
    )
```

Remaining buffered events are flushed synchronously on `close()`.

### RetryingObservabilitySink

Retries transient sink failures with configurable backoff:

```kotlin
import io.github.aeshen.observability.sink.decorator.RetryingObservabilitySink
import io.github.aeshen.observability.sink.decorator.BackoffStrategy

val retrySink =
    RetryingObservabilitySink(
        delegate = mySink,
        maxAttempts = 5,
        backoff = BackoffStrategy.exponential(
            initialDelayMillis = 10,
            multiplier = 2.0,
            maxDelayMillis = 1000,
        ),
        diagnostics = myDiagnostics,
    )

// Or use a fixed delay
val fixedRetrySink =
    RetryingObservabilitySink(
        delegate = mySink,
        maxAttempts = 3,
        backoff = BackoffStrategy.fixed(200),
    )
```

`onRetryExhaustion` on `ObservabilityDiagnostics` is called before the final exception is rethrown.

---

## Profiles

### STANDARD (default)

Best-effort delivery. `IllegalArgumentException` and `IllegalStateException` from sinks are swallowed unless `failOnSinkError = true`.

### AUDIT_DURABLE

Automatically wraps all sinks with retry (5 attempts, exponential backoff), batching (100-event batches, 250 ms flush), and enforces `failOnSinkError = true`. Use for audit compliance scenarios:

```kotlin
val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(File(Path.of("./logs/audit.jsonl"))),
            profile = ObservabilityFactory.Profile.AUDIT_DURABLE,
            diagnostics = myDiagnostics,
        ),
    )
```

---

## Configure Encryption

Encryption is applied as an `ObservabilityProcessor` after encoding. The encrypted output replaces the JSONL payload with a JSONL envelope containing cipher metadata.

### AES-GCM with a fixed key

AES key must be 16, 24, or 32 bytes. The same key is reused for every record.

```kotlin
val rawAesKey = ByteArray(32) { 1 } // Use secure key material in production

val config =
    ObservabilityFactory.Config(
        sinks = listOf(Console),
        encryption = ObservabilityFactory.Config.aesGcmFromRawKeyBytes(rawAesKey),
    )
```

Encrypted output format:

```json
{"alg":"A256GCM","iv":"<base64>","ciphertext":"<base64>"}
```

### RSA-wrapped per-event data key

A fresh AES-256 data key is generated per record and wrapped with RSA-OAEP-SHA256. Only the recipient with the matching private key can decrypt.

```kotlin
import io.github.aeshen.observability.config.encryption.RsaKeyWrapped

val publicKeyPem = """
-----BEGIN PUBLIC KEY-----
...your key...
-----END PUBLIC KEY-----
""".trimIndent()

val config =
    ObservabilityFactory.Config(
        sinks = listOf(File(Path.of("./logs/secure.jsonl"))),
        encryption = RsaKeyWrapped(publicKeyPem),
    )
```

Encrypted output format:

```json
{
  "alg": "A256GCM",
  "iv": "<base64>",
  "wrappedKeyAlg": "RSA-OAEP-256",
  "wrappedKey": "<base64>",
  "ciphertext": "<base64>"
}
```

---

## Sensitive Field Processors

Use `SensitiveFieldProcessor` to mask or remove sensitive fields before sink delivery.
Custom processors run before built-in encryption, so redaction happens on plaintext first.

### Rule model

- Rules are evaluated in declaration order.
- The **first matching rule wins**.
- Match paths are case-insensitive and support `*` globs.
- Common paths:
  - `message`
  - `payload`
  - `context.<key>`
  - `metadata.<key>`

### Example: allow/deny rules

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.File
import io.github.aeshen.observability.processor.builtin.SensitiveFieldProcessor
import io.github.aeshen.observability.processor.builtin.SensitiveFieldRule
import java.nio.file.Path

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(File(Path.of("./logs/audit.jsonl"))),
            processors =
                listOf(
                    SensitiveFieldProcessor(
                        rules = listOf(
                            SensitiveFieldRule.allow("context.requestId"),
                            SensitiveFieldRule.remove("context.password"),
                            SensitiveFieldRule.mask("context.email", "[EMAIL]"),
                            SensitiveFieldRule.mask("metadata.apiToken", "[TOKEN]"),
                            SensitiveFieldRule.mask("message", "[REDACTED]"),
                            SensitiveFieldRule.remove("payload"),
                        ),
                    ),
                ),
            ),
    )
```

### Example: built-in common-secret presets

```kotlin
import io.github.aeshen.observability.processor.builtin.SensitiveFieldPresets

val processor =
    SensitiveFieldProcessor(
        rules = listOf(
            SensitiveFieldRule.allow("context.apiToken"), // explicit allow wins
        ),
        presets = SensitiveFieldPresets.commonSecrets(mask = "[MASKED]"),
    )
```

### Codec compatibility

`SensitiveFieldProcessor` always sanitizes `EncodedEvent.original` and `EncodedEvent.metadata`.
If the encoded payload is a UTF-8 JSON object, it also sanitizes the serialized body.
That means it works out of the box with the default `JsonLineCodec` and with JSON-based custom codecs.

---

## Custom Codec

Replace the default JSONL codec with any `ObservabilityCodec` implementation:

```kotlin
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.ObservabilityCodec

val customCodec =
    ObservabilityCodec { event ->
        EncodedEvent(
            original = event,
            encoded = "custom-format:${event.name.resolvedName()}".toByteArray(Charsets.UTF_8),
        )
    }

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(Console),
            codec = customCodec,
        ),
    )
```

`ObservabilityCodec` is a `fun interface` — a lambda is sufficient.

---

## Extend with Custom Sinks

### Option A: Runtime sink instance via registry adapter

If you already have a sink instance at runtime (for example in tests), wrap it in a local `SinkConfig` and register a provider that returns the instance:

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.registry.SinkRegistry

class MySink : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        println(event.encoded.toString(Charsets.UTF_8))
    }
}

data class DirectSinkConfig(val sink: ObservabilitySink) : SinkConfig

val registry =
    SinkRegistry
        .builder()
        .register<DirectSinkConfig> { config -> config.sink }
        .build()

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(DirectSinkConfig(MySink())),
            sinkRegistry = registry,
        ),
    )
```

### Option B: Config-driven via SinkRegistry

Define a typed `SinkConfig` and register a `SinkProvider` so the sink can be wired through `ObservabilityFactory.Config`:

```kotlin
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.registry.SinkRegistry

data class PartnerSinkConfig(val endpoint: String) : SinkConfig

class PartnerSink(val endpoint: String) : ObservabilitySink {
    override fun handle(event: EncodedEvent) {
        // send to endpoint
    }
}

val registry =
    SinkRegistry
        .defaultBuilder()                          // includes all built-in sinks
        .register<PartnerSinkConfig> { config ->
            PartnerSink(config.endpoint)
        }
        .build()

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(
                Console,
                PartnerSinkConfig("https://partner.example/logs"),
            ),
            sinkRegistry = registry,
        ),
    )
```

Use `SinkRegistry.builder()` (no built-ins) for a fully custom registry, or `SinkRegistry.empty()` for an empty baseline. See `docs/extensions.md` for the full extension contract and `docs/spi-contract.md` for the compatibility policy.

### Sink threading contract

- `handle(event)` may be called concurrently — implementations must be thread-safe or wrapped with `AsyncObservabilitySink`.
- `close()` must release resources and be safe to call more than once.
- Throw from `handle` only for unrecoverable errors; prefer internal retries for transient failures.
- Fatal JVM `Error` types are **never** swallowed by the pipeline.

---

## Diagnostics Hooks

Implement `ObservabilityDiagnostics` to observe runtime reliability signals without side effects on the pipeline:

```kotlin
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink

val diagnostics =
    object : ObservabilityDiagnostics {
        override fun onSinkHandleError(sink: ObservabilitySink, event: EncodedEvent, error: Exception) {
            logger.error("Sink error on ${sink::class.simpleName}: ${error.message}")
        }

        override fun onSinkCloseError(sink: ObservabilitySink, error: Exception) {
            logger.warn("Sink close error: ${error.message}")
        }

        override fun onAsyncDrop(event: EncodedEvent, reason: String) {
            metrics.increment("observability.drop", "reason" to reason)
        }

        override fun onAsyncWorkerError(error: Exception) {
            logger.error("Async worker thread error", error)
        }

        override fun onBatchFlush(batchSize: Int, elapsedMillis: Long, success: Boolean, error: Exception?) {
            metrics.record("observability.batch_flush", batchSize, elapsedMillis)
        }

        override fun onRetryExhaustion(event: EncodedEvent, attempts: Int, lastError: Exception) {
            logger.error("Retry exhausted after $attempts attempts: ${lastError.message}")
        }
    }

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(Console),
            diagnostics = diagnostics,
        ),
    )
```

| Hook                  | Triggered when                                            |
|-----------------------|-----------------------------------------------------------|
| `onSinkHandleError`   | A sink throws during `handle()`                           |
| `onSinkCloseError`    | A sink throws during `close()`                            |
| `onAsyncDrop`         | An event is dropped by the async queue                    |
| `onAsyncWorkerError`  | The async background worker throws an uncaught exception  |
| `onAsyncQueueDepth`   | Async queue depth/capacity changes                        |
| `onAsyncWorkerState`  | Async worker health state changes                         |
| `onBatchFlush`        | A batch is flushed (success or failure)                   |
| `onRetryExhaustion`   | Retry limit exceeded; last error is rethrown              |

---

## Built-in Operational Diagnostics

Use `InMemoryOperationalDiagnostics` when you want first-party counters and health/readiness snapshots without adding heavy dependencies:

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.diagnostics.InMemoryOperationalDiagnostics

val operational = InMemoryOperationalDiagnostics()

val observability =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks = listOf(Console),
            diagnostics = operational,
        ),
    )

// Metrics snapshot (drops, retries, sink failures, batch outcomes, queue depth)
val metrics = operational.metricsSnapshot()

// Health snapshot (READY, DEGRADED, UNHEALTHY + async worker readiness)
val health = operational.healthSnapshot()
```

The collector uses atomics only, keeps runtime overhead small, and works with existing sink implementations.

---

## Java Interoperability

The library is written in Kotlin but is designed to be equally usable from Java. All public APIs expose Java-friendly static methods, explicit constructor overloads, and a fluent `Config.Builder`.

### Creating an instance

```java
import io.github.aeshen.observability.*;
import io.github.aeshen.observability.config.sink.Console;

Observability obs = ObservabilityFactory.create(
    new ObservabilityFactory.Config.Builder(List.of(Console.INSTANCE))
        .profile(ObservabilityFactory.Profile.AUDIT_DURABLE)
        .diagnostics(ObservabilityDiagnostics.NOOP)
        .build()
);
```

### Emitting events

Explicit single- and two-arg overloads are provided for every level so Java callers never need to pass `null` or an empty context:

```java
obs.info(AppEvent.REQUEST_DONE);
obs.info(AppEvent.REQUEST_DONE, "completed in 42ms");
obs.warn(AppEvent.RATE_LIMITED, "quota exceeded", exception);
obs.error(AppEvent.PAYMENT_FAILED, "card declined", exception);
```

### Registering a custom sink

The `register(Class<T>, Function<T, ObservabilitySink>)` overload is callable from Java (the `inline reified` Kotlin overload is not):

```java
SinkRegistry registry = SinkRegistry.defaultBuilder()
    .register(MyHttpSinkConfig.class, cfg -> new MyHttpSink(cfg.getEndpoint()))
    .build();
```

### Decorators

All decorator constructors use `@JvmOverloads`; optional parameters default to sensible values:

```java
ObservabilitySink retrying  = new RetryingObservabilitySink(delegate);
ObservabilitySink batching  = new BatchingObservabilitySink(delegate);
ObservabilitySink async     = new AsyncObservabilitySink(delegate);
```

### Backoff strategies

```java
BackoffStrategy fixed = BackoffStrategy.fixed(100L);
BackoffStrategy exp   = BackoffStrategy.exponential();          // all defaults
BackoffStrategy expCustom = BackoffStrategy.exponential(10L, 2.0, 1000L);
```

### Reading event timestamps

`ObservabilityEvent.getJavaTimestamp()` returns a `java.time.Instant`:

```java
java.time.Instant ts = event.getJavaTimestamp();
Duration age = Duration.between(ts, Instant.now());
```

### Top-level utility classes

The top-level Kotlin files are exposed under intentional Java class names:

| Java class | Contains |
|---|---|
| `ObservabilityEvents` | `event(name, builder)` DSL entry point |
| `KeyGroups` | `put(builder, group)` extension helper |
| `NamespacedKeys` | `putNamespaced(builder, prefix, key, value)` helper |

### Backwards compatibility note

The `@JvmField` annotation on `ObservabilityDiagnostics.NOOP` removes the old `Companion.getNOOP()` getter from the bytecode. The `@file:JvmName` renames mean `ObservabilityEventKt`, `KeyGroupKt`, and `NamespacedKeyKt` no longer exist. All other changes are purely additive. Kotlin callers are unaffected by any of these changes.

---

## Query SPI

The optional `query-spi` module defines a backend-agnostic interface for querying stored audit records. It has no runtime dependencies and is intended for backend-specific implementations (OpenSearch, ClickHouse, PostgreSQL, etc.).

Canonical query field names follow a simple portable convention:

- built-in record fields stay flat: `id`, `timestampEpochMillis`, `level`, `event`, `message`
- dynamic context entries use `context.<key>`
- dynamic metadata entries use `metadata.<key>`

Use `AuditField.context("...")` and `AuditField.metadata("...")` when targeting those dynamic maps.
Reserve `AuditField.custom("...")` for vendor-specific fields outside the shared portable convention.

`query-spi` also includes a backend-neutral translator kit for implementation reuse:

- `AuditSearchQueryTranslator` for recursive criteria traversal
- `StandardAuditFieldMapper` for canonical field mapping (`id`, `timestampEpochMillis`, `level`, `event`, `message`, `context.<key>`, `metadata.<key>`)
- `AuditPredicateFactory`, `AuditSortFactory`, `AuditTextFactory` hooks for backend-specific clause generation
- `ReferenceBackendTranslator` for a complete end-to-end translation example

The reference semantics are intentionally explicit: top-level criteria are `AND`, groups honor their own operator, time bounds are inclusive, text modes map to contains/exact/prefix intent, and pagination is applied after filter/sort.

```kotlin
// query-spi artifact: io.github.aeshen:query-spi:1.0.0

import io.github.aeshen.observability.query.AuditComparisonOperator
import io.github.aeshen.observability.query.AuditCriterion
import io.github.aeshen.observability.query.AuditField
import io.github.aeshen.observability.query.AuditPage
import io.github.aeshen.observability.query.AuditQuery
import io.github.aeshen.observability.query.AuditQueryResult
import io.github.aeshen.observability.query.AuditSearchQuery
import io.github.aeshen.observability.query.AuditSearchQueryService
import io.github.aeshen.observability.query.AuditTextQuery
import io.github.aeshen.observability.query.AuditValue

class MyTypedAuditQueryService : AuditSearchQueryService {
    override fun search(query: AuditSearchQuery): AuditQueryResult {
        // implement backend-specific query
    }
}

val result =
    MyTypedAuditQueryService().search(
        AuditSearchQuery(
            fromEpochMillis = System.currentTimeMillis() - 3_600_000,
            toEpochMillis = System.currentTimeMillis(),
            page = AuditPage(limit = 50, offset = 0),
            criteria =
                listOf(
                    AuditCriterion.Comparison(
                        field = AuditField.LEVEL,
                        operator = AuditComparisonOperator.EQ,
                        value = AuditValue.Text("ERROR"),
                    ),
                    AuditCriterion.Comparison(
                        field = AuditField.context("request_id"),
                        operator = AuditComparisonOperator.EQ,
                        value = AuditValue.Text("req-123"),
                    ),
                    AuditCriterion.Exists(
                        field = AuditField.metadata("ingestedAt"),
                    ),
                ),
            text = AuditTextQuery("payment"),
        ),
    )

result.records.forEach { record ->
    // record.id, record.timestampEpochMillis, record.level,
    // record.event, record.message, record.context, record.metadata
    println(record)
}
// result.total = total matching records before pagination

// Legacy AuditQuery is still supported and can be mapped when needed.
val legacy =
    AuditQuery(
        fromEpochMillis = System.currentTimeMillis() - 3_600_000,
        toEpochMillis = System.currentTimeMillis(),
        filters = mapOf("level" to "ERROR", "context.request_id" to "req-123"),
        freeText = "payment",
    )

val typed = legacy.toSearchQuery()

// If you still need to serve legacy callers, use the deprecated compatibility bridge.
// Prefer implementing AuditSearchQueryService directly for new integrations.
```

---

## OpenTelemetry Setup

### 1) Configure the sink

```kotlin
import io.github.aeshen.observability.config.sink.OpenTelemetry

val obs =
    ObservabilityFactory.create(
        ObservabilityFactory.Config(
            sinks =
                listOf(
                    OpenTelemetry(
                        endpoint = "http://localhost:4318/v1/logs",
                        serviceName = "my-service",
                        serviceVersion = "1.0.0",
                        scheduleDelayMillis = 200,
                        exporterTimeoutMillis = 30_000,
                        maxQueueSize = 2_048,
                        maxExportBatchSize = 512,
                        headers = mapOf("X-Api-Key" to "secret"),
                    ),
                ),
        ),
    )
```

The OTel sink maps `EventLevel` to OTel `Severity`, sets the body to `message` (or event name as fallback), and attaches all context keys as log record attributes prefixed with `context.`.

### 2) Start the local collector

```bash
mkdir -p ./tmp/otel
docker compose -f otel/docker-compose.yml up
```

The included collector configuration (`otel/collector.yaml`) listens on both OTLP gRPC (`4317`) and OTLP HTTP (`4318`) and prints received log records via the `debug` exporter.

### 3) Produce events and verify collector output

Run your application or tests with the `OpenTelemetry` sink configured, then watch the collector:

```bash
docker compose -f otel/docker-compose.yml logs -f otel-collector
```

### 4) Stop the setup

```bash
docker compose -f otel/docker-compose.yml down
```

---

## Conformance Testing

Test fixtures provide a reusable contract test suite for custom sink implementations.

Add the test-fixtures dependency to your sink module:

```kotlin
testImplementation(testFixtures("io.github.aeshen:observability:1.0.0"))
```

Extend `ObservabilitySinkConformanceSuite` and implement the two abstract methods:

```kotlin
import io.github.aeshen.observability.sink.testing.ObservabilitySinkConformanceSuite
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import kotlin.test.Test

class MySinkConformanceTest : ObservabilitySinkConformanceSuite() {
    private val captured = mutableListOf<EncodedEvent>()

    override fun createSubjectSink(): ObservabilitySink = MySink { captured += it }

    override fun observedEvents(): List<EncodedEvent> = captured.toList()

    @Test fun forwardsEvents() = assertForwardsHandledEventBytesAndMetadata()
    @Test fun closeSafety() = assertCloseCanBeCalledRepeatedly()
    @Test fun concurrentSafety() = assertConcurrentHandleSafety()
    @Test fun rejectsAfterClose() = assertCloseRejectsFurtherWritesDeterministically()
}
```

The suite verifies event forwarding, close idempotency, concurrent handle safety, and post-close rejection. See `examples/third-party-sink-example` for a complete working module.

---

## Benchmarks

The `benchmarks` module provides a lightweight load/backpressure harness for sink decorators:

```bash
./gradlew :benchmarks:run
```

Prints elapsed time and events/second for direct vs. async sink configurations.

---

## Run the Test Suite

```bash
./gradlew test
```

The test suite covers:

- API helpers (`ObservabilityApiTest`)
- Pipeline behavior and fan-out (`ObservabilityPipelineTest`)
- Codec encoding (`JsonLineCodecTest`)
- Factory validation (`ObservabilityFactoryTest`)
- Sink implementations (`SinkImplementationsTest`)
- Sink decorators (`SinkDecoratorTest`)
- Sink registry (`SinkRegistryTest`)
- Advanced conformance tests (`AdvancedConformanceTest`)
- OpenTelemetry config validation (`OpenTelemetryConfigTest`)

---

## Code Quality

This project uses a split setup to avoid linting clashes:

- `ktlint` owns Kotlin style/formatting consistency.
- `detekt` owns static analysis and code-quality rules.

```bash
./gradlew ktlintCheck detekt
```

To auto-format Kotlin sources:

```bash
./gradlew ktlintFormat
```

---

## Module Structure

| Module                                  | Description                                                          |
|-----------------------------------------|----------------------------------------------------------------------|
| `:` (root)                              | Core library — pipeline, sinks, codec, encryption, decorators        |
| `:query-spi`                            | Optional: backend-agnostic audit record query SPI                    |
| `:benchmarks`                           | Load/backpressure harness for sink decorator performance             |
| `:examples:third-party-sink-example`    | Example custom sink module with SPI wiring and conformance tests     |

---

## Notes

- `Observability` is `Closeable`; always call `close()` or use `use { }` to flush sinks and release threads.
- `IllegalArgumentException` and `IllegalStateException` from sinks are swallowed by default; set `failOnSinkError = true` for strict propagation.
- Fatal JVM `Error` types are **never** swallowed by the pipeline.
- The default JSONL codec has no external runtime dependencies.
- `opentelemetry-*` and `slf4j-api` are `compileOnly`; they must be on the runtime classpath of your application when those sinks are used.
- Binary compatibility of the public SPI is tracked with `binary-compatibility-validator`. See `api/observability.api` for the current stable surface.
- Patch and minor releases preserve binary compatibility for all stable SPI symbols. See `docs/spi-contract.md`.
- Release workflow and tagging process are documented in `docs/release.md`; user-facing release history is tracked in `CHANGELOG.md`.
- Shared builds resolve from `mavenCentral()` by default for reproducibility. Use `mavenLocal()` only in local ad-hoc testing.
