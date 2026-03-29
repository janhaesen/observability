# Event Schema Contract

This document defines the default encoded event envelope emitted by `JsonLineCodec`.

Canonical machine-readable schema: `docs/schema/event-envelope-v1.schema.json`.

## Versioning

- Current envelope version: `schemaVersion = "1"`
- Additive changes are allowed within the same schema version.
- Breaking changes must increment `schemaVersion`.

## Field Contract (Schema Version 1)

### Required fields

- `schemaVersion` (`String`) - versioned envelope identifier.
- `eventId` (`String`) - UUID generated for each encoded event.
- `name` (`String`) - resolved event name.
- `level` (`String`) - event level (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`).
- `timestamp` (`String`) - event timestamp in ISO-8601 format.
- `message` (`String | null`) - optional message.
- `context` (`Object`) - flattened context key/value map.
- `payloadPresent` (`Boolean`) - distinguishes absent payload from empty payload.
- `payloadBase64` (`String`) - Base64 representation of payload bytes.

### Optional fields

- `correlationId` (`String | null`) - mirrors `context.id` when present.
- `error` (`Object`) - present when a throwable is attached.
  - `type` (`String`)
  - `message` (`String | null`)
  - `stacktrace` (`String`)

## JSON Example

```json
{
  "schemaVersion": "1",
  "eventId": "dc7fc4d6-f5e3-4ca0-a0da-0f258f5f79ac",
  "correlationId": "req-123",
  "name": "request.done",
  "level": "INFO",
  "timestamp": "2026-03-21T10:00:00Z",
  "message": "Request completed",
  "context": {
    "id": "req-123",
    "status_code": 200
  },
  "payloadPresent": false,
  "payloadBase64": ""
}
```

