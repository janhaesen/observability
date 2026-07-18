# Observability sidecar

The sidecar receives versioned HTTP event submissions and emits them through the core
`Observability` pipeline. Its source-of-truth contract is
[`src/main/openapi/sidecar-v1.yaml`](./src/main/openapi/sidecar-v1.yaml); Kotlin API
models are generated during `:sidecar:compileKotlin`.

## Run locally

```bash
./gradlew :sidecar:run --no-daemon
curl -X POST http://127.0.0.1:8080/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"name":"request.done","level":"INFO","timestamp":"2026-07-18T12:00:00Z","context":{}}'
```

`SIDECAR_BEARER_TOKEN` enables bearer authentication. Without it, `SIDECAR_HOST` must
resolve to a loopback address. `SIDECAR_PROFILE=AUDIT_DURABLE` requires
`SIDECAR_PERSISTENT_BUFFER_DIRECTORY`; a successful `202` then means the event was
written to that journal, not that an external sink has acknowledged delivery.

## Container image

```bash
docker build -f sidecar/Dockerfile -t observability-sidecar:local .
docker run --rm -p 8080:8080 \
  -e SIDECAR_BEARER_TOKEN=change-me \
  -e SIDECAR_PERSISTENT_BUFFER_DIRECTORY=/var/lib/observability \
  -v observability-journal:/var/lib/observability \
  observability-sidecar:local
```

The release workflow publishes `ghcr.io/<owner>/observability-sidecar` with the release
tag and immutable commit-SHA tag. Mount persistent storage whenever `AUDIT_DURABLE` is used.
