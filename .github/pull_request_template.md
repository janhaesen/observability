## What changed

<!-- Briefly describe the change and why -->

## Type of change

- [ ] Feature
- [ ] Bug fix
- [ ] Refactor
- [ ] Docs only
- [ ] Build/CI
- [ ] Release prep

## Scope touched

- [ ] Core pipeline (`ObservabilityFactory` / `ObservabilityPipeline`)
- [ ] Providers (`ContextProvider` / `MetadataEnricher` / built-in enrichers)
- [ ] Built-in sinks / sink decorators (`Console`, `File`, `ZipFile`, `OpenTelemetry`, `Http`)
- [ ] SPI contracts (`SinkProvider` / `SinkConfig` / codec / enrichers)
- [ ] Codec / processors / encryption
- [ ] Reliability / diagnostics (`retry`, `batch`, `async`, `ObservabilityDiagnostics`)
- [ ] `:query-spi`
- [ ] `:benchmarks`
- [ ] `:examples:third-party-sink-example`
- [ ] Docs (`README.md`, `docs/*`, module READMEs)

## Validation

- [ ] Added/updated tests
- [ ] Ran `./gradlew test`
- [ ] Ran module-specific tests for touched modules (for example `:query-spi:test`, `:examples:third-party-sink-example:test`)
- [ ] Ran `./gradlew apiCheck`
- [ ] Ran `./gradlew ktlintCheck`
- [ ] Ran `./gradlew detekt`
- [ ] (If applicable) ran benchmark/example module checks

## Compatibility & risk

- [ ] No stable SPI breakage (`docs/spi-contract.md`)
- [ ] If SPI changed, migration notes included
- [ ] Provider ordering/merge behavior reviewed (context + metadata precedence)
- [ ] `AUDIT_DURABLE` behavior reviewed when reliability logic changed
- [ ] Sink failure semantics reviewed when changing delivery/retry behavior

## Docs & release notes

- [ ] Updated root and module docs where user-visible behavior changed (`README.md`, `query-spi/README.md`, `examples/*/README.md`)
- [ ] Updated `CHANGELOG.md` (if needed)
- [ ] Changelog bullets map clearly to affected module(s) and API/SPI/docs impact
- [ ] Synced `agent.md` and `docs/agent/agent.full.md`

## Notes for reviewers

<!-- Anything important to focus on, trade-offs, known limitations -->
