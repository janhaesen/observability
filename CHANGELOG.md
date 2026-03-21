# Changelog

All notable changes to this project are documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## [1.0.0] - 2026-03-21

### Added
- Query SPI model validation for safer backend integrations.
- CI quality gates for static analysis, API compatibility checks, CVE scanning, and publish dry-runs.
- Release workflow for tagged versions with generated GitHub release notes.

### Changed
- `Console` sink now writes encoded payload bytes directly to stdout.
- `Slf4j` sink now forwards encoded payloads (and attached throwables) to SLF4J.
- Repository versioning moved from `1.0.0-SNAPSHOT` to stable `1.0.0`.
- Shared dependency resolution no longer includes `mavenLocal()` by default.

