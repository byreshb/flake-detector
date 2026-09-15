# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `Reports`, `MarkdownReport` and `HtmlReport`: a ranked table with a per-test trend, plus
  explanations of the top suspects, in Markdown or as one self-contained HTML file with the trend
  drawn as an inline SVG bar chart. `flake score --format html`.
- `QuarantineLedger` (`.flake/quarantine.yaml`, entries capped at a 90-day expiry) and the
  `flake quarantine add|remove|list|check` commands. `flake gate`, which reads the current
  build's reports and exits non-zero only for a failure that is neither quarantined (and
  unexpired) nor above the flakiness threshold, printing its reasoning for every failure.
  `docs/quarantine.md` and `docs/ci-integration.md` document the ledger and a full workflow.
- `FlakinessScorer`: rerun-recovery rate, flip rate and failure-message entropy combined into a
  0-1 score with Wilson intervals and an `explain()`; runner and hour-of-day correlations
  (Cramér's V) reported alongside. Formulas in `docs/scoring.md`, fixtures in
  `conformance/scoring.json`. The `flake score` command with Markdown and JSON output.
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
