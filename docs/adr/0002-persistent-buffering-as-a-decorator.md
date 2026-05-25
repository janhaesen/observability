# ADR 0002: Model persistent buffering as a sink decorator

Persistent buffering is a reliability concern that should compose with existing sinks rather than replace them, so the library will expose it as a public sink decorator with its own durable journal and replay semantics. This keeps disk-backed durability aligned with the existing reliability vocabulary (`AsyncObservabilitySink`, `BatchingObservabilitySink`, `RetryingObservabilitySink`) while making the delivery contract explicit: once persisted, events are replayed in order until acknowledged by the wrapped delegate.
