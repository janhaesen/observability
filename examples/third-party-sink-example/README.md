# Third-party Sink Example

This example shows how an external module can provide:

- custom `SinkConfig`
- `SinkProvider` integration
- sink conformance tests via test fixtures

It is meant as a copy-and-adapt reference for library consumers who want to publish their own sink artifact without forking the main project.

## What this example demonstrates

- defining a sink-specific config type
- wiring that config into a `SinkRegistry`
- implementing `ObservabilitySink`
- validating behavior with `ObservabilitySinkConformanceSuite`

## How to use it

Read this example together with:

- [`docs/extensions.md`](../../docs/extensions.md) for the extension contract
- [`docs/spi-contract.md`](../../docs/spi-contract.md) for compatibility expectations
- the root [`README.md`](../../README.md) section on custom sinks and conformance testing

When building your own sink module, copy the structure and tests, but adapt the config shape, transport logic, and packaging to your backend.

## Run Tests

```bash
./gradlew :examples:third-party-sink-example:test
```

## Recommended workflow

1. Start from the config + provider pattern shown here.
2. Add conformance tests before transport-specific edge cases.
3. Add backend-specific tests for retries, close behavior, and error reporting after the conformance baseline passes.
