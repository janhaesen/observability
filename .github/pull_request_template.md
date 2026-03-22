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
- [ ] Sinks / sink decorators
- [ ] Codec / processors / encryption
- [ ] `:query-spi`
- [ ] `:benchmarks`
- [ ] `:examples:third-party-sink-example`
- [ ] Docs

## Validation

- [ ] Added/updated tests
- [ ] Ran `./gradlew test`
- [ ] Ran `./gradlew apiCheck`
- [ ] Ran `./gradlew detekt`
- [ ] (If applicable) ran benchmark/example module checks

## Compatibility & risk

- [ ] No stable SPI breakage (`docs/spi-contract.md`)
- [ ] If SPI changed, migration notes included
- [ ] `AUDIT_DURABLE` behavior reviewed when reliability logic changed

## Docs & release notes

- [ ] Updated `README.md`/docs where user-visible behavior changed
- [ ] Updated `CHANGELOG.md` (if needed)
- [ ] Synced `agent.md` and `docs/agent/agent.full.md`

## Notes for reviewers

<!-- Anything important to focus on, trade-offs, known limitations -->
