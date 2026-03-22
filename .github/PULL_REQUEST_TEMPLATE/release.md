## Release Summary

- Release version: `vX.Y.Z`
- Scope: <!-- patch / minor / major -->
- Target branch: <!-- usually main -->

## Pre-release checklist (from `docs/release.md`)

- [ ] Updated `CHANGELOG.md` with the planned version section
- [ ] CI is green for quality gates (`test`, `apiCheck`, `detekt`, CVE scan, publish dry-run)
- [ ] Committed version/changelog updates

## Verification Evidence

### Core checks

- [ ] `./gradlew test apiCheck detekt --no-daemon` executed
- [ ] `./gradlew publish --dry-run --no-daemon` executed

### Output / links

- [ ] Paste key command output snippets or CI links
- [ ] Confirm artifacts are produced as expected

## Compatibility & Stability

- [ ] No unintended stable SPI breakage (see `docs/spi-contract.md`)
- [ ] If SPI changed, migration notes are included
- [ ] Binary compatibility expectations validated for this release type

## Docs & Meta

- [ ] `README.md` and relevant docs updated for user-visible changes
- [ ] `agent.md` and `docs/agent/agent.full.md` synchronized
- [ ] Release notes bullets are ready (or linked)

## Tagging Plan

- [ ] Tag to create after merge: `vX.Y.Z`
- [ ] Confirmed that pushing `v*` tag triggers release workflow

## Reviewer Focus

- [ ] Version/changelog correctness
- [ ] Quality gate completeness
- [ ] Release risk / rollback notes included

## Additional Notes

<!-- Risks, known issues, rollback instructions, follow-up work -->
