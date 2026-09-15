package io.github.byreshb.flake.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestCommandTest {

  private static final Path FIXTURES = Path.of("../flake-core/src/test/resources/surefire");

  @TempDir Path tmp;

  @Test
  void ingestsADirectoryIntoTheDatabase() {
    Path db = tmp.resolve("history.db");
    CliTestSupport cli = new CliTestSupport();

    int status =
        cli.run(
            "ingest",
            FIXTURES.toString(),
            "--db",
            db.toString(),
            "--commit",
            "abc",
            "--build-id",
            "1",
            "--branch",
            "main",
            "--runner",
            "mac");

    assertThat(status).isZero();
    assertThat(cli.out.toString())
        .contains("Ingested 2 file(s), 13 run(s), 13 new")
        .contains("build 1 attempt 1, commit abc")
        .contains("History now holds 13 run(s) over 1 build(s)");
    try (SqliteRunStore store = SqliteRunStore.open(db)) {
      assertThat(store.runsOf(TestId.parse("com.acme.CheckoutTest#appliesCoupon"))).hasSize(3);
      assertThat(store.runsOf(TestId.parse("com.acme.SearchTest#findsNothing")).get(0).runner())
          .isEqualTo("mac");
    }
  }

  @Test
  void ingestingTwiceAddsNothing() {
    Path db = tmp.resolve("history.db");
    CliTestSupport cli = new CliTestSupport();
    String[] args = {
      "ingest", FIXTURES.toString(), "--db", db.toString(), "--commit", "abc", "--build-id", "1"
    };

    assertThat(cli.run(args)).isZero();
    assertThat(cli.run(args)).isZero();

    assertThat(cli.out.toString()).contains("13 run(s), 0 new");
  }

  @Test
  void secondAttemptOfTheSameBuildIsStoredSeparately() {
    Path db = tmp.resolve("history.db");
    CliTestSupport cli = new CliTestSupport();

    cli.run(
        "ingest", FIXTURES.toString(), "--db", db.toString(), "--commit", "abc", "--build-id", "1");
    cli.run(
        "ingest",
        FIXTURES.toString(),
        "--db",
        db.toString(),
        "--commit",
        "abc",
        "--build-id",
        "1",
        "--attempt",
        "2");

    assertThat(cli.out.toString()).contains("26 run(s) over 2 build(s)");
  }

  @Test
  void failsWhenNoReportsMatch() throws Exception {
    Path empty = Files.createDirectories(tmp.resolve("empty"));
    CliTestSupport cli = new CliTestSupport();

    int status = cli.run("ingest", empty.toString(), "--db", tmp.resolve("h.db").toString());

    assertThat(status).isEqualTo(1);
    assertThat(cli.err.toString()).contains("No files matching TEST-*.xml");
    assertThat(Files.exists(tmp.resolve("h.db"))).isFalse();
  }

  @Test
  void customGlobFindsOtherFiles() {
    CliTestSupport cli = new CliTestSupport();

    int status =
        cli.run(
            "ingest",
            FIXTURES.toString(),
            "--glob",
            "testsuites-*.xml",
            "--db",
            tmp.resolve("h.db").toString(),
            "--commit",
            "abc",
            "--build-id",
            "1");

    assertThat(status).isZero();
    assertThat(cli.out.toString()).contains("Ingested 1 file(s), 2 run(s)");
  }

  @Test
  void helpAndVersionWork() {
    CliTestSupport cli = new CliTestSupport();

    assertThat(cli.run("--help")).isZero();
    assertThat(cli.out.toString()).contains("ingest");
    assertThat(cli.run("--version")).isZero();
    assertThat(cli.out.toString()).contains("flake ");
    assertThat(cli.run("ingest", "--help")).isZero();
    assertThat(cli.out.toString()).contains("--glob").contains("github");
    assertThat(cli.run("ingest", "github", "--help")).isZero();
    assertThat(cli.out.toString()).contains("--repo").contains("--workflow");
  }

  @Test
  void unknownSubcommandIsAUsageError() {
    CliTestSupport cli = new CliTestSupport();

    assertThat(cli.run("bogus")).isEqualTo(2);
    assertThat(cli.err.toString()).contains("Unmatched argument");
  }
}
