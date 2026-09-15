package io.github.byreshb.flake.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateCommandTest {

  private static final Path FIXTURES = Path.of("../flake-core/src/test/resources/surefire");
  private static final TestId REJECTS = TestId.parse("com.acme.CheckoutTest#rejectsExpiredCard");
  private static final TestId LOADS = TestId.parse("com.acme.CheckoutTest#loadsInventory");

  @TempDir Path tmp;

  private String db() {
    return tmp.resolve("history.db").toString();
  }

  private String ledger() {
    return tmp.resolve(".flake/quarantine.yaml").toString();
  }

  @Test
  void failsOnUnexcusedFailuresAndListsThem() {
    CliTestSupport cli = new CliTestSupport();

    int status =
        cli.run("gate", "--reports", FIXTURES.toString(), "--db", db(), "--ledger", ledger());

    assertThat(status).isEqualTo(1);
    String out = cli.out.toString();
    assertThat(out).contains("FAIL  com.acme.CheckoutTest#rejectsExpiredCard: not quarantined");
    assertThat(out).contains("FAIL  com.acme.CheckoutTest#loadsInventory: not quarantined");
    assertThat(out).doesNotContain("appliesCoupon");
    assertThat(out).contains("2 failure(s), 0 excused, 2 must be fixed.");
  }

  @Test
  void passesWhenBothFailuresAreQuarantined() {
    quarantine(REJECTS, "2026-09-01", "2026-11-30");
    quarantine(LOADS, "2026-09-01", "2026-11-30");
    CliTestSupport cli = new CliTestSupport();

    int status =
        cli.run("gate", "--reports", FIXTURES.toString(), "--db", db(), "--ledger", ledger());

    assertThat(status).isZero();
    String out = cli.out.toString();
    assertThat(out)
        .contains(
            "SKIP  com.acme.CheckoutTest#rejectsExpiredCard: quarantined by o until 2026-11-30");
    assertThat(out)
        .contains("SKIP  com.acme.CheckoutTest#loadsInventory: quarantined by o until 2026-11-30");
    assertThat(out).contains("2 failure(s), 2 excused, 0 must be fixed.");
  }

  @Test
  void anExpiredQuarantineDoesNotExcuseTheFailure() {
    quarantine(REJECTS, "2026-01-01", "2026-02-01");
    CliTestSupport cli = new CliTestSupport();

    int status =
        cli.run("gate", "--reports", FIXTURES.toString(), "--db", db(), "--ledger", ledger());

    assertThat(status).isEqualTo(1);
    assertThat(cli.out.toString())
        .contains(
            "FAIL  com.acme.CheckoutTest#rejectsExpiredCard: quarantine expired on 2026-02-01");
  }

  @Test
  void aHighFlakinessScoreExcusesTheFailureWithoutQuarantine() {
    seedFlakyHistory(LOADS);
    CliTestSupport cli = new CliTestSupport();

    int status =
        cli.run("gate", "--reports", FIXTURES.toString(), "--db", db(), "--ledger", ledger());

    assertThat(status).isEqualTo(1);
    String out = cli.out.toString();
    assertThat(out)
        .containsPattern(
            "SKIP {2}com.acme.CheckoutTest#loadsInventory: flakiness score 0\\.[0-9]+ > threshold"
                + " 0.300");
    assertThat(out).contains("FAIL  com.acme.CheckoutTest#rejectsExpiredCard: not quarantined");
    assertThat(out).contains("1 must be fixed.");
  }

  @Test
  void noFailuresPassesTheGate() {
    CliTestSupport cli = new CliTestSupport();

    int status =
        cli.run(
            "gate",
            "--reports",
            FIXTURES.toString(),
            "--glob",
            "TEST-com.acme.SearchTest.xml",
            "--db",
            db(),
            "--ledger",
            ledger());

    assertThat(status).isZero();
    assertThat(cli.out.toString()).contains("No failures in 1 report file(s); gate passes.");
  }

  @Test
  void missingOrEmptyReportsDirectoryFails() {
    CliTestSupport cli = new CliTestSupport();

    int status = cli.run("gate", "--reports", tmp.resolve("nope").toString(), "--db", db());

    assertThat(status).isEqualTo(1);
    assertThat(cli.err.toString()).contains("No files matching");
  }

  private void quarantine(TestId testId, String added, String expires) {
    CliTestSupport add = new CliTestSupport();
    int status =
        add.run(
            "quarantine",
            "add",
            testId.toString(),
            "--ledger",
            ledger(),
            "--reason",
            "known issue",
            "--owner",
            "o",
            "--added",
            added,
            "--expires",
            expires);
    assertThat(status).as(add.err.toString()).isZero();
  }

  private void seedFlakyHistory(TestId testId) {
    // A Surefire rerun that recovers, on ten different commits: the strongest flakiness signal
    // (rerun recovery and a flip), the same shape as the "textbook flaky" conformance fixture.
    List<TestRun> runs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      BuildRun build =
          new BuildRun(
              "b" + i, "c" + i, 1, Instant.parse("2026-01-01T00:00:00Z").plus(Duration.ofDays(i)));
      runs.add(
          new TestRun(
              testId,
              build,
              "main",
              "linux",
              0,
              Duration.ofMillis(1),
              Outcome.FAIL,
              "h" + (i % 3)));
      runs.add(
          new TestRun(testId, build, "main", "linux", 1, Duration.ofMillis(1), Outcome.PASS, null));
    }
    try {
      Files.createDirectories(Path.of(db()).getParent());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    try (SqliteRunStore store = SqliteRunStore.open(Path.of(db()))) {
      store.record(runs);
    }
  }
}
