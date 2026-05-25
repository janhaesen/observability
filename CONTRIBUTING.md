# Contributing

Thanks for contributing to `observability`.

## Before you open a PR

1. Read the root [`README.md`](./README.md) for the user-facing project shape.
2. Read [`docs/spi-contract.md`](./docs/spi-contract.md) if your change touches extension points or `query-spi`.
3. Read [`docs/release.md`](./docs/release.md) if your change affects versioning, release automation, or changelog flow.

## Local validation

Run the same baseline checks the repository expects in CI:

```bash
./gradlew test apiCheck ktlintCheck detekt --no-daemon
```

Useful focused commands:

```bash
./gradlew :query-spi:test --no-daemon
./gradlew :examples:third-party-sink-example:test --no-daemon
./gradlew publish --dry-run --no-daemon
```

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
