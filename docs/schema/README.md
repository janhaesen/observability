# Schema Directory

This directory holds **machine-readable contracts** for emitted observability payloads.

## Current contents

| File | Meaning |
| --- | --- |
| `event-envelope-v1.schema.json` | canonical JSON Schema for the default `JsonLineCodec` envelope |

## How to use these files

- Treat the schema files as the canonical contract for tooling, validation, and downstream integrations.
- Keep the matching prose explanation in [`../event-schema.md`](../event-schema.md) aligned with the schema.
- Additive changes stay within the same schema version; breaking changes require a new schema versioned file.

When changing `JsonLineCodec`, update the schema and its documentation in the same change.
