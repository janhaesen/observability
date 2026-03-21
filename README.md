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
import io.github.aeshen.observability.sink.ObservabilitySink

class MySink : ObservabilitySink {
	override fun handle(event: io.github.aeshen.observability.codec.EncodedEvent) {
		println(event.encoded.toString(Charsets.UTF_8))
	}
}

val obs =
	ObservabilityFactory.create(sinks = listOf(MySink()))
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

## Notes

- `Observability` is `Closeable`; call `close()` or use `use { ... }`.
- Sink failures are swallowed by default; set `failOnSinkError = true` for strict behavior.
- Current codec output is JSON text with context and base64 payload fields.
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

