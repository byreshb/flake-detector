# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `SqliteRunStore` (`.flake/history.db`, numbered SQL migrations applied on open, idempotent
  inserts), `RunSource` with `LocalDirectorySource`, and the `flake ingest <dir>` command with
  build identity taken from options, the GitHub Actions environment or git.
- Run model (`TestId`, `BuildRun`, `TestRun`, `Outcome`) and `JUnitXmlParser` for Surefire and
  Failsafe reports, including Surefire 3 rerun elements (`flakyFailure`, `rerunFailure` and the
  error variants) which become one run per execution.
- Multi-module Maven build (`flake-core`, `flake-github`, `flake-junit`, `flake-cli`) with
  automatic formatting (Spotless, google-java-format), JaCoCo coverage gate at 85%, GitHub
  Actions CI and release workflows.

[Unreleased]: https://github.com/byreshb/flake-detector/compare/main...HEAD
