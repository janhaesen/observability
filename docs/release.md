# Release Process

This repository uses a PR-first release flow that is compatible with branch protection rules on `main`.

## Versioning

- Follow Semantic Versioning (`MAJOR.MINOR.PATCH`).
- `gradle.properties` stores the current released version in `VERSION_NAME`.
- This repository does not use snapshot versions on `main`; after a release, `main` may continue to show the latest released version until the next release PR bumps it on a `release/vX.Y.Z` branch.
- Public API compatibility is validated with `apiCheck` before release.

## Create a release PR

1. Update `CHANGELOG.md` with the target version section.
2. Merge the release-ready changes into `main`.
3. Run the `Create Release PR` workflow from GitHub Actions with the version number (for example `1.1.0`).

The `Create Release PR` workflow will:

- validate semantic version input,
- require the actor to be in `RELEASE_MANAGERS`,
- require approval through the `release` environment,
- require `CHANGELOG.md` to contain the release section,
- ensure `release/vX.Y.Z` branch and PR do not already exist,
- update `VERSION_NAME` in `gradle.properties`,
- open a release-labelled PR targeting `main`.

The opened PR will then require the `quality-gates` status check to pass (via `ci.yml`) before it can be merged.

## CI, security, and release checks

Keep these distinct when reviewing release readiness:

- **Quality gates (`ci.yml`)** run `test`, `apiCheck`, `ktlintCheck`, `detekt`, and `publish --dry-run`.
- **Security scanning** runs in a separate PR workflow (`osv-scanner-pr.yml`).
- **Workflow linting** runs separately when `.github/workflows/**` changes.

Release prep should reference all relevant signals rather than implying they come from one workflow.

### Authorization setup

- Configure repository variable `RELEASE_MANAGERS` with a comma-separated list of usernames.
- Configure environment `release` with required reviewers.
- Create a `release` label once in repository settings.
- Enable repository setting `Allow GitHub Actions to create and approve pull requests` when using `GITHUB_TOKEN` for PR creation.
- If that setting cannot be enabled, add secret `RELEASE_PR_TOKEN` (fine-grained PAT with `contents: write` and `pull requests: write`) and the workflow will use it for PR operations.

## Tag and publish a release

When that PR is merged:

- `.github/workflows/release-tag.yml` tags the PR merge commit as `vX.Y.Z`.
- `.github/workflows/release-tag.yml` only tags PRs merged from `release/vX.Y.Z`.
- Pushing that tag triggers `.github/workflows/release.yml` (tag pushes only), which:
  - re-runs quality gates,
  - publishes artifacts to GitHub Packages,
  - builds artifacts,
  - creates a GitHub Release with generated notes,
  - requires approval through the `release` environment.

## Deprecated workflow

`.github/workflows/prepare-release.yml` is intentionally deprecated because it pushed directly to `main`, which conflicts with repository rules that require pull requests.
