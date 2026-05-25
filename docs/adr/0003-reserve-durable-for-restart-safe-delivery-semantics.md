# ADR 0003: Reserve "durable" for restart-safe delivery semantics

The library distinguishes **best-effort**, **strict**, and **durable** delivery as separate concepts. **Durable delivery** refers only to journal-backed delivery that survives process restarts and replays unacknowledged events, which today is provided by `PersistentObservabilitySink`. The existing `AUDIT_DURABLE` profile name remains for compatibility, but documentation treats it as an audit-oriented strict/retry/batch profile rather than the canonical definition of durable delivery.
