# OpenTelemetry Local Setup

This directory contains the local collector setup used by the repository examples and manual OpenTelemetry sink checks.

## Contents

| File | Purpose |
| --- | --- |
| `collector.yaml` | collector pipeline configuration |
| `docker-compose.yml` | launches a local collector with OTLP gRPC (`4317`) and OTLP HTTP (`4318`) exposed |

## Common commands

```bash
docker compose -f otel/docker-compose.yml up
docker compose -f otel/docker-compose.yml logs -f otel-collector
docker compose -f otel/docker-compose.yml down
```

For the end-to-end example and sink wiring details, see the [OpenTelemetry Setup section in the root README](../README.md#opentelemetry-setup).
