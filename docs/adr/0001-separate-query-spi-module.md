# ADR 0001: Keep the audit query SPI in a separate Gradle module

- Status: Accepted
- Date: 2026-05-25

## Context

The repository exposes two related but different surfaces:

1. the core `observability` library for event emission, processing, and sink delivery
2. an optional audit query abstraction used by backend-specific query implementations

The query APIs are valuable to some adopters, but many applications only emit events and never need audit-record retrieval. The repository already reflects this split through the `:query-spi` module and separate documentation.

## Decision

Keep the audit query abstractions in the dedicated `:query-spi` Gradle module instead of folding them into the root library artifact.

## Consequences

- Consumers that only emit events do not need to depend on the query SPI.
- Backend authors get a lightweight, backend-agnostic contract they can implement independently of the core sink/runtime pipeline.
- Compatibility for query contracts can be documented and reviewed as a distinct surface alongside the sink SPI.
- Repository documentation should keep pointing query backend authors to `query-spi/README.md` rather than treating query support as part of the minimum core path.
