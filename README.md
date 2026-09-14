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

The shape of the rest of the tool as the plan lands:

```bash
flake ingest github --repo byreshb/playwright-pagefactory --workflow CI --runs 200
flake score --top 20 --format md
flake quarantine add com.acme.CheckoutTest#appliesCoupon --reason "timing on CI" --owner byresh --expires 2026-12-01
flake gate --reports target/surefire-reports
```

### Command reference

| Command                    | What it does                                                         |
|----------------------------|----------------------------------------------------------------------|
| `flake ingest <dir>`       | Read `TEST-*.xml` under `<dir>` (recursively) into the run history.  |

Options shared by every command: `--db FILE` (default `.flake/history.db`). Options of `ingest`:
`--glob`, `--commit`, `--branch`, `--runner`, `--build-id`, `--attempt`.

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
