# Flake Detector

[![CI](https://github.com/byreshb/flake-detector/actions/workflows/ci.yml/badge.svg)](https://github.com/byreshb/flake-detector/actions/workflows/ci.yml)

Statistical flaky-test detection and quarantine for JUnit and Maven projects. Feed it your
Surefire and Failsafe reports, run after run, and it tells you which tests are unreliable, how
sure it is, and why. It is deliberately not AI-based: most red builds are not regressions, and the
fix is run history and statistics, not retries and guesswork.

## The problem

Every team with a big enough test suite has the same routine. A build goes red, someone glances at
the failing test, says "that one is flaky", and clicks re-run. If it goes green the build ships;
nobody records that the failure happened. When a test fails often enough somebody disables it in a
chat thread and promises to come back to it. Nobody does. Years later the quarantine list is full
of tests no one remembers, some of them guarding code that has since broken for real, and the
answer to "which of our tests are actually unreliable?" is a shrug.

Three things are missing, and none of them exist for the Java and Maven ecosystem today:

1. **Run history.** A single failure says nothing. The same test failing on the same commit, then
   passing on re-run, says a lot. That needs a store of every outcome across builds, commits,
   attempts and runners.
2. **Ranking with a confidence interval.** A test that flipped once in three runs is not more
   suspicious than one that flipped thirty times in three hundred. The score has to say how much
   evidence is behind it, and explain which signals contributed.
3. **Quarantine with an expiry.** A quarantined test needs an owner, a reason, an issue link and a
   date by which it is either fixed or the quarantine fails the build. Quarantine that cannot
   expire is deletion with extra steps.

This project provides all three: a run store fed from local report directories or GitHub Actions
artifacts, a scorer that ranks tests by flip rate, rerun recovery and failure-message entropy with
a Wilson interval, and a quarantine ledger enforced by a JUnit 5 extension and a CI gate.

## Status

Under construction; the modules and the build are in place, the features are being delivered one
by one (see the delivery plan in [docs/design.md](docs/design.md)). Not on Maven Central yet;
publishing there is planned, see [docs/releasing.md](docs/releasing.md).

## Requirements

- Java 17 or newer
- Maven 3.9 or newer

## Install

Build once on your machine and install into your local Maven repository (`~/.m2`), after which any
project on that machine can depend on the modules:

```bash
git clone https://github.com/byreshb/flake-detector.git
cd flake-detector
mvn install
```

Then add the module you need to your own project's `pom.xml`:

```xml
<dependency>
  <groupId>io.github.byreshb</groupId>
  <artifactId>flake-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The command line tool is a single executable jar at `flake-cli/target/flake-cli-<version>.jar`.
Put a wrapper on your path:

```bash
alias flake='java -jar /path/to/flake-detector/flake-cli/target/flake-cli-1.0.0-SNAPSHOT.jar'
```

## Quick start

Record the reports of every build into the run history. After `mvn test` (or `mvn verify` for
Failsafe) in your project:

```bash
flake ingest target/surefire-reports
```

Run it in every CI job and cache `.flake/history.db` between runs; on GitHub Actions the commit,
branch, run id, attempt and runner are picked up from the environment automatically. Anywhere
else, commit and branch come from git and you can override any of them:

```bash
flake ingest build/reports --glob '*.xml' --commit 9fceb02 --branch main --build-id 4711 --attempt 2 --runner mac-mini-3
```

Ingesting the same reports twice does nothing: a run is identified by test, build, attempt and
rerun index.

Then rank the tests:

```bash
flake score --top 20 --format md
```

```
| # | Test | Score | Runs | Failures | Recovered | Flips | Messages | Trend |
|--:|------|------:|-----:|---------:|----------:|------:|---------:|-------|
| 1 | `com.acme.CheckoutTest#appliesCoupon` | 0.383 | 16 | 6 | 4 | 5/6 | 3 | `.F.F.F.F...` |
| 2 | `com.acme.CheckoutTest#loadsInventory` | 0.112 | 4 | 4 | 0 | 0/0 | 3 | `EEEE` |

com.acme.CheckoutTest#appliesCoupon: score 0.383 over 16 run(s)
  rerun recovery   0.300 x 0.5 = 0.150  (4 of 6 failure(s) passed on a retry of the same commit; rate 0.67, 95% interval [0.30, 0.90])
  flip rate        0.436 x 0.3 = 0.131  (5 of 6 consecutive same-commit pair(s) changed outcome; rate 0.83, 95% interval [0.44, 0.97])
  message entropy  0.511 x 0.2 = 0.102  (3 distinct message(s) over 6 failure(s); entropy 0.61, shrunk to 0.51)
  reported only:   runner correlation 0.03, hour-of-day correlation 0.16
```

The score is a weighted sum of three signals, each measured only between runs of the **same
commit** so that real fixes and regressions do not count: failures that passed on a retry (weight
0.5), consecutive runs that changed outcome (0.3), and how many different failure messages the
test produces (0.2). Rates enter the score as the lower bound of a 95% Wilson interval, so one
lucky rerun does not outrank three hundred. A test that always fails scores 0: that is a bug, not
flakiness. Every formula, with a worked example, is in [docs/scoring.md](docs/scoring.md). The
`Trend` column is one character per run, oldest first (`.` pass, `F` fail, `E` error);
`--format html` draws the same trend as a small inline SVG bar chart next to a ranked table and
the same explanations, in one self-contained file:

```bash
flake score --format html > report.html
```

When a test is worth taking out of the way, quarantine it with an owner, a reason and an expiry
of at most 90 days:

```bash
flake quarantine add com.acme.CheckoutTest#appliesCoupon \
  --reason "timing on CI" --owner byresh --expires 2026-12-01
```

Then gate the build on the reports of the current run: it fails only for a failure that is
neither quarantined nor already known to be flaky, and prints its reasoning either way.

```bash
flake gate --reports target/surefire-reports
```

```
SKIP  com.acme.CheckoutTest#appliesCoupon: quarantined by byresh until 2026-12-01 (timing on CI)
FAIL  com.acme.CheckoutTest#chargesTax: not quarantined, flakiness score 0.050 <= threshold 0.300

2 failure(s), 1 excused, 1 must be fixed.
```

Run `flake quarantine check` as a separate CI step so an expired entry fails the build even on a
day when nothing else does. See [docs/quarantine.md](docs/quarantine.md) for the ledger format
and policy, and [docs/ci-integration.md](docs/ci-integration.md) for a full GitHub Actions
workflow wiring `ingest`, `gate` and `quarantine check` together.

The shape of the rest of the tool as the plan lands:

```bash
flake ingest github --repo byreshb/playwright-pagefactory --workflow CI --runs 200
flake pr-comment
flake issues sync
```

### Command reference

| Command                       | What it does                                                              |
|--------------------------------|----------------------------------------------------------------------------|
| `flake ingest <dir>`           | Read `TEST-*.xml` under `<dir>` (recursively) into the run history.        |
| `flake score`                  | Rank tests by flakiness: `--top N` (20; 0 for all), `--format md\|html\|json`, `--explain N` (3). |
| `flake quarantine add <test>`  | Add or replace an entry: `--reason`, `--owner`, `--expires` (required), `--added` (today), `--issue`. |
| `flake quarantine remove <test>` | Remove an entry.                                                          |
| `flake quarantine list`        | List entries as a Markdown table; `--expired-only` to filter.              |
| `flake quarantine check`       | Exit non-zero and list every expired entry.                                |
| `flake gate`                   | Exit non-zero only for unexcused failures: `--reports DIR` (required), `--glob`, `--threshold` (0.3). |

Options shared by every command: `--db FILE` (default `.flake/history.db`). `quarantine` and
`gate` also take `--ledger FILE` (default `.flake/quarantine.yaml`). Options of `ingest`:
`--glob`, `--commit`, `--branch`, `--runner`, `--build-id`, `--attempt`.

`--format json` prints an array with one object per test holding every component: `test`,
`score`, `runs`, `failures`, `flipPairs`, `flips`, `flipRate`, `flipRateLower`, `flipRateUpper`,
`recoveredFailures`, `rerunRecoveryRate`, `rerunRecoveryLower`, `distinctMessages`, `entropy`,
`entropyComponent`, `runnerCorrelation`, `hourCorrelation`.

## Reference

### Reading Surefire and Failsafe reports

`JUnitXmlParser` in `flake-core` reads one report file into a list of `TestCaseResult`, one per
`testcase` element. Each result holds the test id, the reported time and the list of executions
in the order they happened, so Surefire 3 reruns (`-Dsurefire.rerunFailingTestsCount=2`) are
preserved rather than collapsed into a single pass or fail:

| Elements under `testcase`                    | Executions produced                     |
|----------------------------------------------|-----------------------------------------|
| none (or only `system-out` / `system-err`)   | PASS                                    |
| `skipped`                                    | SKIPPED                                 |
| `failure` or `error`, plus N `rerunFailure` / `rerunError` | FAIL/ERROR, then N more FAIL/ERROR |
| N `flakyFailure` / `flakyError`              | N FAIL/ERROR, then PASS                 |

```java
JUnitXmlParser parser = new JUnitXmlParser();
BuildRun build = new BuildRun("run-42", "9fceb02", 1, Instant.now());
for (TestCaseResult result : parser.parse(Path.of("target/surefire-reports/TEST-CheckoutTest.xml"))) {
  List<TestRun> runs = result.toRuns(build, "main", "ubuntu-latest");
  System.out.println(result.testId() + " " + result.last().outcome() + " after " + runs.size() + " execution(s)");
}
```

### The run store

`RunStore` is the history; `SqliteRunStore.open(path)` creates `.flake/history.db` (and its
parent directories) and applies the numbered migration scripts bundled in the jar, so opening an
older database upgrades it in place. `record` ignores runs that are already present, and every
query returns runs chronologically (build start time, attempt, rerun index):

```java
try (RunStore store = SqliteRunStore.open(SqliteRunStore.DEFAULT_PATH)) {
  RunSource source = new LocalDirectorySource(Path.of("target/surefire-reports"), build, "main", "");
  int added = store.record(source.read());
  for (TestId id : store.testIds()) {
    List<TestRun> history = store.runsOf(id);
    System.out.println(id + ": " + history.size() + " run(s), " + added + " just added");
  }
}
```

### Scoring

`FlakinessScorer` turns a test's runs into a `FlakeScore`; `scoreAll(store)` scores every test in
ranking order. `explain()` lists each component with its value, weight and contribution.

```java
FlakinessScorer scorer = new FlakinessScorer();
try (RunStore store = SqliteRunStore.open(SqliteRunStore.DEFAULT_PATH)) {
  for (FlakeScore score : scorer.scoreAll(store)) {
    if (score.score() > 0.3) {
      System.out.print(score.explain());
    }
  }
}
```

The formulas are fixed by [`conformance/scoring.json`](conformance/scoring.json), a set of run
histories with their expected components; the Java scorer is tested against it and the TypeScript
scorer in the GitHub Action will be too.

### Reports

`Reports.build(store, scorer, trendPoints)` scores every test and attaches its recent trend (the
last `trendPoints` non-skipped outcomes, oldest first) as a `ReportEntry`; `MarkdownReport` and
`HtmlReport` both render a `List<ReportEntry>` so the two formats can never drift out of sync with
each other or with the scorer:

```java
try (RunStore store = SqliteRunStore.open(SqliteRunStore.DEFAULT_PATH)) {
  List<ReportEntry> entries = Reports.build(store, new FlakinessScorer(), Reports.DEFAULT_TREND_POINTS);
  Files.writeString(Path.of("report.html"), HtmlReport.render("Flake score", entries, 5));
}
```

`HtmlReport` produces one self-contained file: inline CSS, no external resources, safe to attach
to a CI run as an artifact or check into the repository. Each row's trend is a small inline SVG
bar chart: a short green bar for a pass, a tall red bar for a failure, a tall maroon bar for an
error.

### The quarantine ledger

`QuarantineLedger` reads and writes `.flake/quarantine.yaml`, an immutable value with `add`,
`remove`, `find`, `isQuarantined` and `expired`. Building a `QuarantineEntry` with an expiry more
than 90 days after it was added throws, whether the entry comes from `flake quarantine add` or
from loading a hand-edited file:

```java
QuarantineLedger ledger = QuarantineLedger.load(QuarantineLedger.DEFAULT_PATH);
QuarantineEntry entry = new QuarantineEntry(
    TestId.parse("com.acme.CheckoutTest#appliesCoupon"), "timing on CI", "byresh",
    LocalDate.now(), LocalDate.now().plusDays(30), null);
ledger.add(entry).save(QuarantineLedger.DEFAULT_PATH);
```

See [docs/quarantine.md](docs/quarantine.md) for the file format and the policy behind the
expiry.

Failure messages are never stored as text. Each failed execution carries a 64-bit hash of the
message after normalisation (exception type prefixed, whitespace collapsed, every run of digits
replaced by `#`), which is enough to tell "the same assertion keeps failing" from "it fails
differently every time" without keeping possibly sensitive output.

## Modules

| Module         | What it holds                                                                         |
|----------------|---------------------------------------------------------------------------------------|
| `flake-core`   | Run model, JUnit XML parser, SQLite run store, flakiness scorer, quarantine ledger, reports |
| `flake-github` | GitHub REST client, ingestion from Actions artifacts, PR comment and issue sync       |
| `flake-junit`  | JUnit 5 `QuarantineExtension`                                                         |
| `flake-cli`    | The `flake` command (picocli), shaded into one executable jar                         |

## Building and testing

```bash
mvn test                # unit tests in every module
mvn verify              # tests + coverage report in */target/site/jacoco (fails under 85% lines)
mvn spotless:check      # verify formatting without changing anything (for CI)
mvn javadoc:javadoc     # API docs in */target/site/apidocs
```

Formatting is automatic: every build runs [Spotless](https://github.com/diffplug/spotless) with
google-java-format (Google style, annotations on their own line) over the sources before
compiling, so you never need to format by hand.

### Continuous integration

Every push and pull request runs the GitHub Actions workflow in `.github/workflows/ci.yml`:
formatting check, `mvn verify` across all modules, and upload of the Surefire reports.

### Releasing

1. Move the `Unreleased` notes in `CHANGELOG.md` under a new version heading and set that
   version with `mvn versions:set`.
2. Commit, then tag and push: `git tag -a v1.2.3 -m "Release 1.2.3" && git push origin v1.2.3`.
3. The release workflow in `.github/workflows/release.yml` checks the tag matches the pom,
   builds the jars, and publishes a GitHub Release with the changelog section as its notes.

Full steps, including the planned but not yet configured Maven Central publishing, are in
[docs/releasing.md](docs/releasing.md).

## License

Apache License 2.0, see [LICENSE](LICENSE).
