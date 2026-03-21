# Release Process

This repository uses tagged releases and GitHub release notes.

## Versioning

- Follow Semantic Versioning (`MAJOR.MINOR.PATCH`).
- `main` tracks the next stable release version (no `-SNAPSHOT`).
- Public API compatibility is validated with `apiCheck` before release.

## Pre-release checklist

1. Update `CHANGELOG.md` with the planned version section.
2. Ensure CI is green (`test`, `apiCheck`, `detekt`, CVE scan, publish dry-run).
3. Commit version and changelog updates.

## Create a release

```bash
./gradlew test apiCheck detekt --no-daemon
./gradlew publish --dry-run --no-daemon
git tag v1.0.0
git push origin v1.0.0
```

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which:
- re-runs quality gates,
- builds artifacts,
- creates a GitHub Release with generated notes.

