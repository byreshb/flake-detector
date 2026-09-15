# Continuous integration

How to wire `flake` into a GitHub Actions workflow so a red build only blocks on failures that
are neither quarantined nor already known to be flaky.

## Minimal workflow

Run the build as normal, then let `flake` decide the exit code from the reports it produced,
using run history cached between builds:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven

      # .flake/history.db persists flakiness evidence across builds; without it every build
      # starts from zero evidence and flake gate can never excuse a failure by score.
      - name: Restore run history
        uses: actions/cache@v4
        with:
          path: .flake/history.db
          key: flake-history-${{ github.run_id }}
          restore-keys: flake-history-

      - name: Build and test
        run: mvn -B test
        continue-on-error: true
        id: build

      - name: Download the flake CLI
        run: |
          curl -sLo flake.jar \
            https://github.com/byreshb/flake-detector/releases/latest/download/flake-cli.jar

      - name: Record this build's runs
        run: java -jar flake.jar ingest target/surefire-reports

      - name: Fail only on unexcused failures
        run: java -jar flake.jar gate --reports target/surefire-reports

      - name: Fail if a quarantine entry has expired
        run: java -jar flake.jar quarantine check
```

`continue-on-error: true` on the test step matters: if Maven's own failure stopped the job, `flake
gate` would never run and a genuinely flaky test would block the build exactly as before. `gate`
is what decides whether the job actually fails.

## Order of steps

1. **Restore** `.flake/history.db` from the cache before testing (there is nothing to ingest into
   yet, but restoring first means this run's `ingest` adds to prior evidence instead of starting
   over).
2. **Run the tests**, without letting a failure stop the job.
3. **Ingest** this build's reports, so today's run becomes evidence for tomorrow's score.
4. **Gate** on the same reports: fails the job only for a failure that is not quarantined and
   scores at or below the threshold (default 0.3, see [docs/scoring.md](scoring.md)).
5. **Check** the quarantine ledger separately, so an expired entry fails the build even on a day
   when nothing else does (see [docs/quarantine.md](quarantine.md)).
6. **Save** `.flake/history.db` back to the cache (`actions/cache` does this automatically at the
   end of the job when the restored key did not match exactly, which `restore-keys` guarantees
   here since the key includes `github.run_id`).

## What each exit code means

| Step               | Exit code | Meaning                                                                |
|---------------------|-----------|--------------------------------------------------------------------------|
| `flake gate`        | 0         | Every failure was quarantined or scored at or below the threshold.       |
| `flake gate`        | 1         | At least one failure needs a real fix (or a new quarantine entry).       |
| `flake quarantine check` | 0     | No quarantine entry has expired.                                         |
| `flake quarantine check` | 1     | At least one entry expired; fix the test or run `quarantine add` again.  |

`gate` always prints its reasoning (`SKIP` or `FAIL` per test) so the exit code is never a
mystery; see the examples in [docs/quarantine.md](quarantine.md#how-quarantine-changes-what-flake-gate-does).

## Pull requests

Run the same steps on `pull_request`. A new test that fails on its first run has no history, so it
scores 0 and `gate` fails the build, as it should. Once `flake-github` and the PR comment step
land (delivery steps 7 and 9), the same information will also be posted as a PR comment instead of
only living in the job log.
