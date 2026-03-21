# observability

An opinionated Kotlin framework for application observability.

It gives you one entry point to emit structured events and route them to one or more sinks (console, SLF4J, file, zip), with optional encryption.

## Features

- Unified event API (`trace`, `debug`, `info`, `warn`, `error`, and `emit`)
- Type-safe contextual metadata with typed keys
- Multiple sinks with fan-out support
- Optional record encryption (AES-GCM, or RSA-wrapped per-record data key)
- Best-effort sink handling by default, with strict mode support

## Install

This project is currently configured as:

- `group`: `io.github.aeshen`
- `artifact`: `observability`
- `version`: `1.0.0-SNAPSHOT`

If you consume it from another Gradle project, add your repository and dependency as usual.

```kotlin
dependencies {
	implementation("io.github.aeshen:observability:1.0.0-SNAPSHOT")
	// Required only when using the OpenTelemetry sink
	implementation("io.opentelemetry:opentelemetry-api:1.49.0")
	implementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.49.0")
	// Required only when using the Slf4j sink
	implementation("org.slf4j:slf4j-api:2.0.17")
}
```

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
		val context =
			ObservabilityContext
				.builder()
				.put(StringKey.REQUEST_ID, "req-123")
				.put(StringKey.PATH, "/health")
				.put(LongKey.STATUS_CODE, 200L)
				.build()

		it.info(
			name = AppEvent.APP_START,
			message = "Application started",
		)

		it.info(
			name = AppEvent.REQUEST_DONE,
			message = "Request completed",
			context = context,
		)
	}
}
```

## Configure Sinks

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.config.sink.File
import io.github.aeshen.observability.config.sink.OpenTelemetry
import io.github.aeshen.observability.config.sink.Slf4j
import io.github.aeshen.observability.config.sink.ZipFile
import java.nio.file.Path

val config =
	ObservabilityFactory.Config(
		sinks = listOf(
			Console,
			Slf4j(MyService::class),
			File(Path.of("./logs/events.jsonl")),
			ZipFile(Path.of("./logs/events.zip")),
			OpenTelemetry(
				endpoint = "http://localhost:4318/v1/logs",
				serviceName = "my-service",
			),
		),
		failOnSinkError = false,
	)

val observability = ObservabilityFactory.create(config)
```

## Configure Encryption

### AES-GCM with a fixed key

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.Console

val rawAesKey = ByteArray(32) { 1 } // Use secure key material in real code.

val config =
	ObservabilityFactory.Config(
		sinks = listOf(Console),
		encryption = ObservabilityFactory.Config.aesGcmFromRawKeyBytes(rawAesKey),
	)
```

### RSA-wrapped per-event data key

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.encryption.RsaKeyWrapped
import io.github.aeshen.observability.config.sink.File

val publicKeyPem = """
-----BEGIN PUBLIC KEY-----
...your key...
-----END PUBLIC KEY-----
""".trimIndent()

val config =
	ObservabilityFactory.Config(
		sinks = listOf(File(java.nio.file.Path.of("./logs/secure.jsonl"))),
		encryption = RsaKeyWrapped(publicKeyPem),
	)
```

## Extend With Custom Sink Implementations

For details see `docs/extensions.md`.

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.SinkConfig
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.sink.registry.SinkRegistry

class MySink : ObservabilitySink {
	override fun handle(event: io.github.aeshen.observability.codec.EncodedEvent) {
		println(event.encoded.toString(Charsets.UTF_8))
	}
}

val obs =
	ObservabilityFactory.create(MySink())

data class PartnerSinkConfig(val endpoint: String) : SinkConfig

val registry =
	SinkRegistry
		.builder()
		.register<PartnerSinkConfig> { MySink() }
		.build()
```

## Use a Custom Codec

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.ObservabilityCodec

val customCodec =
	ObservabilityCodec { event ->
		EncodedEvent(
			original = event,
			encoded = "custom-format".toByteArray(Charsets.UTF_8),
		)
	}

val observability =
	ObservabilityFactory.create(
		ObservabilityFactory.Config(
			sinks = listOf(io.github.aeshen.observability.config.sink.Console),
			codec = customCodec,
		),
	)
```


## Build Events with the DSL

```kotlin
import io.github.aeshen.observability.event
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.sink.EventLevel

val e =
	event(AppEvent.REQUEST_DONE) {
		level(EventLevel.INFO)
		message("Request completed")
		context(StringKey.REQUEST_ID, "req-123")
	}

observability.emit(e)
```

## Namespaced Context Keys

```kotlin
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.key.StringKey
import io.github.aeshen.observability.key.putNamespaced

val context =
	ObservabilityContext
		.builder()
		.putNamespaced("request", StringKey.PATH, "/orders")
		.putNamespaced("request", StringKey.METHOD, "GET")
		.build()
```

## Add Global Context With ContextProvider

```kotlin
import io.github.aeshen.observability.ContextProvider
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.key.StringKey

val actorProvider =
	ContextProvider {
		ObservabilityContext
			.builder()
			.put(StringKey.USER_AGENT, "admin-user")
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

## Audit Hardened Profile

```kotlin
val observability =
	ObservabilityFactory.create(
		ObservabilityFactory.Config(
			sinks = listOf(Console),
			profile = ObservabilityFactory.Profile.AUDIT_DURABLE,
		),
	)
```

`AUDIT_DURABLE` enables strict sink errors and applies retry + batching wrappers for durable delivery defaults.

## Runtime Diagnostics Hooks

```kotlin
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics

val diagnostics =
	object : ObservabilityDiagnostics {
		override fun onAsyncDrop(event: io.github.aeshen.observability.codec.EncodedEvent, reason: String) {
			println("drop reason=$reason")
		}
	}
```

Pass diagnostics via `ObservabilityFactory.Config(diagnostics = diagnostics)`.

## Notes

- `Observability` is `Closeable`; call `close()` or use `use { ... }`.
- Sink failures from `Exception` are swallowed by default; set `failOnSinkError = true` for strict behavior.
- The default codec writes one JSON object per line including `name`, `level`, `timestamp`, `message`, `error`, `context`, and `payloadBase64`.
- Optional search/query SPI lives in `query-spi` for backend-specific query modules.
- Sink SPI compatibility policy: `docs/spi-contract.md`.
- Third-party sink sample module: `examples/third-party-sink-example`.
- Backpressure/load harness: `benchmarks`.

## Run the Test Suite

The project includes tests for API helpers, pipeline behavior, codec encoding, factory validation, and sink implementations.

```bash
./gradlew test
```

## OpenTelemetry Setup

The project now includes a native OpenTelemetry sink that exports logs to an OTLP HTTP endpoint.

### 1) Configure the sink

```kotlin
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.OpenTelemetry

val obs =
	ObservabilityFactory.create(
		ObservabilityFactory.Config(
			sinks =
				listOf(
					OpenTelemetry(
						endpoint = "http://localhost:4318/v1/logs",
						serviceName = "my-service",
						scheduleDelayMillis = 200,
						exporterTimeoutMillis = 30_000,
						maxQueueSize = 2_048,
						maxExportBatchSize = 512,
					),
				),
		),
	)
```

### 2) Start the collector

```bash
mkdir -p ./tmp/otel
docker compose -f otel/docker-compose.yml up
```

The included collector listens on both OTLP gRPC (`4317`) and OTLP HTTP (`4318`) and prints received log records through the `debug` exporter.

### 3) Produce events and verify collector output

- Run your app/tests with `OpenTelemetry`
- Check collector logs for exported OTLP log records

```bash
docker compose -f otel/docker-compose.yml logs -f otel-collector
```

### 4) Stop the setup

```bash
docker compose -f otel/docker-compose.yml down
```

