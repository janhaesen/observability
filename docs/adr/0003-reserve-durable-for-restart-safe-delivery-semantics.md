# ADR 0003: Reserve "durable" for restart-safe delivery semantics

The library distinguishes **best-effort**, **strict**, and **durable** delivery as separate concepts. **Durable delivery** refers only to journal-backed delivery that survives process restarts and replays unacknowledged events. `AUDIT_DURABLE` now composes `PersistentObservabilitySink` with retrying delivery and therefore provides canonical durable delivery when configured with a persistent buffer directory.
