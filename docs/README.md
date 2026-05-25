# Documentation Map

Use this directory as the **map of deep references**, not as a second project overview.

## Start here

| Need | Read |
| --- | --- |
| Shared repository overview | [`../CONTEXT.md`](../CONTEXT.md) |
| User-facing API and setup | [`../README.md`](../README.md) |
| Contributor workflow and validation | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) |
| Quick agent-oriented repo context | [`../agent.md`](../agent.md) |
| Full agent-oriented deep context | [`agent/agent.full.md`](./agent/agent.full.md) |

## Architecture and contracts

| Topic | File |
| --- | --- |
| Extension seams for sinks, enrichers, and processors | [`extensions.md`](./extensions.md) |
| Compatibility policy for stable SPI surfaces | [`spi-contract.md`](./spi-contract.md) |
| Encoded event envelope contract | [`event-schema.md`](./event-schema.md) |
| Machine-readable schemas | [`schema/README.md`](./schema/README.md) |
| Recorded architecture decisions | [`adr/`](./adr/) |

## Operations and release

| Topic | File |
| --- | --- |
| Release workflow and versioning | [`release.md`](./release.md) |
| Local OpenTelemetry collector support | [`../otel/README.md`](../otel/README.md) |

## Documentation rules of thumb

- Keep top-level context thin and stable.
- Prefer colocated READMEs when a directory's purpose is not obvious.
- Prefer contracts and examples over prose when behavior can be made explicit.
- Update docs in the same change when public behavior, contributor workflow, or extension contracts change.
