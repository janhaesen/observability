# Context Providers

Context providers supply ambient runtime metadata that is automatically merged into every emitted
event without the caller having to pass it explicitly. They sit at the very beginning of the
event pipeline, before encoding.

## Pipeline position

```
ObservabilityEvent (emitted by caller)
      │
      ▼
ContextProviders   ← You are here (merge ambient context into event)
      │
      ▼
Codec              (encode event → EncodedEvent with bytes)
      │
      ▼
MetadataEnrichers  (enrich metadata map)
      │
      ▼
Processors         (e.g., encryption, transformation)
      │
      ▼
Sinks              (fan-out delivery)
```

## Merge precedence

Context providers are applied **before** event-level context. Multiple providers are merged in the
order they are registered. If two providers supply the same key, the **later provider wins**.
After all providers have been merged, the **event-level context is applied last**, so any key set
explicitly on the event always takes the highest precedence.

```
provider[0] → provider[1] → … → provider[N] → event context  (highest precedence)
```

## Built-in providers

### MdcContextProvider

Reads all entries from the SLF4J MDC at emit time and attaches them to the event.

**Requires:** `slf4j-api` on the runtime classpath.

```kotlin
import io.github.aeshen.observability.context.provider.MdcContextProvider

val observability = ObservabilityFactory.create(
    ObservabilityFactory.Config(
        sinks = listOf(/* ... */),
        contextProviders = listOf(MdcContextProvider()),
    )
)
```

MDC entries are prefixed with `"mdc."` by default:

```kotlin
MDC.put("requestId", "abc-123")
MDC.put("userId", "user-42")

observability.info(MyEvent.ORDER_PLACED)
// → event context: { "mdc.requestId": "abc-123", "mdc.userId": "user-42" }
```

#### Custom prefix

```kotlin
// entries become "request.requestId", "request.userId", …
MdcContextProvider(prefix = "request")

// entries use the MDC key name directly (no prefix)
MdcContextProvider(prefix = "")
```

If the MDC is empty or unavailable at emit time, an empty context is returned silently.

---

### OpenTelemetryContextProvider

Reads the active OpenTelemetry span context and attaches trace/span correlation identifiers.

**Requires:** `opentelemetry-api` on the runtime classpath and an active span on the calling thread.

```kotlin
import io.github.aeshen.observability.context.provider.OpenTelemetryContextProvider

val observability = ObservabilityFactory.create(
    ObservabilityFactory.Config(
        sinks = listOf(/* ... */),
        contextProviders = listOf(OpenTelemetryContextProvider()),
    )
)
```

Inside an active span, trace correlation is attached automatically:

```kotlin
val span = tracer.spanBuilder("processOrder").startSpan()
span.makeCurrent().use {
    observability.info(MyEvent.ORDER_PLACED)
    // → event context: {
    //     "trace.id":    "4bf92f3577b34da6a3ce929d0e0e4736",
    //     "trace.span_id": "00f067aa0ba902b7",
    //     "trace.flags": "01"
    //   }
}
span.end()
```

#### Keys injected

| Key              | Value                          |
|------------------|--------------------------------|
| `trace.id`       | 32-char lowercase hex trace ID |
| `trace.span_id`  | 16-char lowercase hex span ID  |
| `trace.flags`    | 2-char hex trace-flags byte    |

The enum `OpenTelemetryContextProvider.OtelKey` holds typed references to all three keys.

If no valid span context is active, an empty context is returned silently.

---

### CoroutineContextProvider

Propagates a structured [ObservabilityContext] through Kotlin coroutine scopes using a thread-local
backed `CoroutineContext` element. This bridges structured concurrency with the non-suspend
`ContextProvider` interface.

**Requires:** `kotlinx-coroutines-core` on the runtime classpath.

#### Setup

```kotlin
import io.github.aeshen.observability.context.provider.CoroutineContextProvider

val observability = ObservabilityFactory.create(
    ObservabilityFactory.Config(
        sinks = listOf(/* ... */),
        contextProviders = listOf(CoroutineContextProvider()),
    )
)
```

#### Usage

Wrap coroutine code with `withObservabilityContext` to install ambient context:

```kotlin
import io.github.aeshen.observability.context.provider.withObservabilityContext

withObservabilityContext(
    ObservabilityContext.builder()
        .put(StringKey.REQUEST_ID, "req-abc")
        .build()
) {
    processOrder()              // any emit() here picks up "req-abc"
    updateInventory()           // same
}
```

#### Nesting

Calls to `withObservabilityContext` may be nested. The innermost context takes effect within its
own block, and the outer context is automatically restored on exit:

```kotlin
withObservabilityContext(outerCtx) {
    observability.info(MyEvent.OUTER)       // uses outerCtx

    withObservabilityContext(innerCtx) {
        observability.info(MyEvent.INNER)   // uses innerCtx
    }

    observability.info(MyEvent.OUTER_AGAIN) // outerCtx is restored
}
```

#### Thread safety

`ObservabilityCoroutineContext` implements `ThreadContextElement`, so the thread-local is
correctly saved and restored around every suspension point. The ambient context remains consistent
even when coroutines are dispatched across threads (e.g., `Dispatchers.IO`).

#### Direct element usage

If you need to propagate context through a coroutine scope without `withObservabilityContext`, you
can add the element directly to the coroutine context:

```kotlin
launch(ObservabilityCoroutineContext(myContext)) {
    observability.info(MyEvent.BACKGROUND_JOB)
}
```

---

## Custom context providers

Any class implementing `ContextProvider` can be registered:

```kotlin
val observability = ObservabilityFactory.create(
    ObservabilityFactory.Config(
        sinks = listOf(/* ... */),
        contextProviders = listOf(
            MdcContextProvider(),
            OpenTelemetryContextProvider(),
            ContextProvider {
                ObservabilityContext.builder()
                    .put(StringKey.USER_AGENT, determineUserAgent())
                    .build()
            },
        ),
    )
)
```

## Combining providers

Providers are merged in registration order. The event-level context always wins last:

```kotlin
contextProviders = listOf(
    MdcContextProvider(),           // applied first
    OpenTelemetryContextProvider(), // applied second (overrides MDC on conflict)
    CoroutineContextProvider(),     // applied third (overrides OTel on conflict)
    // event context is always applied last and has highest precedence
)
```

## Web / server application example

For a typical Ktor or Spring application you might combine all three providers:

```kotlin
val observability = ObservabilityFactory.create(
    ObservabilityFactory.Config(
        sinks = listOf(/* ... */),
        contextProviders = listOf(
            MdcContextProvider(),            // MDC keys set by filters / interceptors
            OpenTelemetryContextProvider(),  // OTel trace correlation
            CoroutineContextProvider(),      // request-scoped context from coroutine scope
        ),
    )
)

// In a request handler (Ktor example):
suspend fun ApplicationCall.handleRequest() {
    val requestCtx = ObservabilityContext.builder()
        .put(StringKey.REQUEST_ID, request.headers["X-Request-Id"] ?: UUID.randomUUID().toString())
        .put(StringKey.PATH, request.path())
        .put(StringKey.METHOD, request.httpMethod.value)
        .build()

    withObservabilityContext(requestCtx) {
        observability.info(AppEvent.REQUEST_RECEIVED)
        processRequest()
    }
}
```

