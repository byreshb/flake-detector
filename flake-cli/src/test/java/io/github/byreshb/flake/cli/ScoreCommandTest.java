package io.github.byreshb.flake.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScoreCommandTest {

  private static final Path FIXTURES = Path.of("../flake-core/src/test/resources/surefire");

  @TempDir Path tmp;
  private String db;

  @BeforeEach
  void ingestTwoBuilds() {
    db = tmp.resolve("history.db").toString();
    CliTestSupport cli = new CliTestSupport();
    assertThat(
            cli.run(
                "ingest", FIXTURES.toString(), "--db", db, "--commit", "abc", "--build-id", "1"))
        .isZero();
    assertThat(
            cli.run(
                "ingest",
                FIXTURES.toString(),
                "--db",
                db,
                "--commit",
                "abc",
                "--build-id",
                "1",
                "--attempt",
                "2"))
        .isZero();
  }

  @Test
  void printsARankedMarkdownTableWithExplanations() {
    CliTestSupport cli = new CliTestSupport();

    int status = cli.run("score", "--db", db);

    assertThat(status).isZero();
    String out = cli.out.toString();
    assertThat(out).startsWith("| # | Test | Score |");
    assertThat(out.lines().filter(l -> l.startsWith("| 1 |")).findFirst().orElseThrow())
        .contains("`com.acme.CheckoutTest#appliesCoupon`");
    assertThat(out).contains("com.acme.CheckoutTest#appliesCoupon: score 0.");
    assertThat(out).contains("rerun recovery");
    assertThat(out).doesNotContain("com.acme.SearchTest#findsNothing: score");
  }

  @Test
  void topLimitsTheTable() {
    CliTestSupport cli = new CliTestSupport();

    cli.run("score", "--db", db, "--top", "2", "--explain", "0");

    String out = cli.out.toString();
    assertThat(out.lines().filter(l -> l.startsWith("| ") && !l.startsWith("| #")).count())
        .isEqualTo(2);
    assertThat(out).doesNotContain("rerun recovery");
  }

  @Test
  void jsonListsEveryComponent() {
    CliTestSupport cli = new CliTestSupport();

    int status = cli.run("score", "--db", db, "--format", "json", "--top", "0");

    assertThat(status).isZero();
    String out = cli.out.toString();
    assertThat(out)
        .startsWith("[\n  {\"test\": \"com.acme.CheckoutTest#appliesCoupon\", \"score\": 0.");
    assertThat(out).contains("\"rerunRecoveryLower\": ").contains("\"hourCorrelation\": ");
    assertThat(out.lines().filter(l -> l.startsWith("  {")).count()).isEqualTo(8);
    assertThat(out.strip()).endsWith("]");
  }

  @Test
  void emptyHistoryIsReported() {
    CliTestSupport cli = new CliTestSupport();

    int status = cli.run("score", "--db", tmp.resolve("empty.db").toString());

    assertThat(status).isZero();
    assertThat(cli.out.toString()).contains("No runs in the history yet");
  }

  @Test
  void jsonEscapesStrings() {
    String control = String.valueOf((char) 1);
    assertThat(ScoreJson.quote("a\"b\\c\nd\te\r" + control))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\te\\r\\" + "u0001\"");
    assertThat(ScoreJson.number(0.5)).isEqualTo("0.500000");
  }
}
