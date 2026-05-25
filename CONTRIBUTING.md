# Contributing

Thanks for contributing to `observability`.

## Before you open a PR

1. Read the root [`README.md`](./README.md) for the user-facing project shape.
2. Read [`CONTEXT.md`](./CONTEXT.md) and [`docs/README.md`](./docs/README.md) for the repository map and doc index.
3. Read [`docs/spi-contract.md`](./docs/spi-contract.md) if your change touches extension points or `query-spi`.
4. Read [`docs/release.md`](./docs/release.md) if your change affects versioning, release automation, or changelog flow.

## Project structure at a glance

| Path | Purpose |
| --- | --- |
| `src/main/kotlin/io/github/aeshen/observability/` | core public API, pipeline, sinks, codecs, processors, diagnostics |
| `src/test/` | behavior-focused tests for the core library |
| `src/testFixtures/` | shared conformance and support fixtures consumed by examples and extension modules |
| `query-spi/` | optional audit query contracts for backend authors |
| `benchmarks/` | comparative performance scenarios |
| `examples/third-party-sink-example/` | reference external sink/provider module |
| `api/observability.api` | binary compatibility baseline managed by the Kotlin binary compatibility validator |

## Local validation

Run the same baseline checks the repository expects in CI:

```bash
./gradlew test apiCheck ktlintCheck detekt --no-daemon
```

Useful focused commands:

```bash
./gradlew :query-spi:test --no-daemon
./gradlew :examples:third-party-sink-example:test --no-daemon
./gradlew apiDump --no-daemon
./gradlew publish --dry-run --no-daemon
```

Use `apiDump` when you intentionally change the published binary API and need to refresh `api/observability.api` after reviewing the compatibility impact.

## Contribution expectations

- Keep user-facing docs in sync with behavior changes.
- Update `CHANGELOG.md` for user-visible changes.
- Add or update tests when changing runtime behavior.
- Treat sink SPI and `query-spi` changes carefully; review compatibility expectations in [`docs/spi-contract.md`](./docs/spi-contract.md).
- When changing release workflows or templates, keep the docs and automation aligned.

## Pull requests

Use the pull request template and include enough context for reviewers to understand:

- what changed
- why it changed
- how it was validated
- any compatibility or migration impact

If your change affects contributors or operators, update the relevant docs in the same PR.
