package io.github.byreshb.flake.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.score.FlakinessScorer;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownReportTest {

  private static final TestId FLAKY = TestId.parse("com.acme.CheckoutTest#appliesCoupon");

  @Test
  void rendersARankedTableWithATrendColumnAndExplanations() {
    List<TestRun> runs =
        List.of(run(0, 0, Outcome.FAIL), run(0, 1, Outcome.PASS), run(1, 0, Outcome.PASS));

    try (SqliteRunStore store = SqliteRunStore.inMemory()) {
      store.record(runs);
      List<ReportEntry> entries = Reports.build(store, new FlakinessScorer(), 10);

      String md = MarkdownReport.render(entries, 5);

      assertThat(md)
          .startsWith(
              "| # | Test | Score | Runs | Failures | Recovered | Flips | Messages | Trend |\n");
      assertThat(md).contains("| 1 | `com.acme.CheckoutTest#appliesCoupon` |");
      assertThat(md).contains("`F..`");
      assertThat(md).contains("com.acme.CheckoutTest#appliesCoupon: score 0.");
      assertThat(md).contains("rerun recovery");
    }
  }

  @Test
  void explainStopsAtTheLimitAndAtAZeroScore() {
    List<ReportEntry> entries =
        List.of(
            new ReportEntry(new FlakinessScorer().score(FLAKY, List.of()), List.of()),
            new ReportEntry(
                new FlakinessScorer().score(TestId.parse("a.B#c"), List.of()), List.of()));

    String md = MarkdownReport.render(entries, 5);

    assertThat(md).doesNotContain("score 0.000 over");
  }

  @Test
  void sparklineMapsOutcomesToCharacters() {
    assertThat(MarkdownReport.sparkline(List.of(Outcome.PASS, Outcome.FAIL, Outcome.ERROR)))
        .isEqualTo(".FE");
    assertThat(MarkdownReport.sparkline(List.of())).isEmpty();
  }

  private static TestRun run(int day, int rerun, Outcome outcome) {
    BuildRun build =
        new BuildRun(
            "b" + day,
            "c" + day,
            1,
            Instant.parse("2026-01-01T00:00:00Z").plus(Duration.ofDays(day)));
    return new TestRun(
        FLAKY,
        build,
        "main",
        "linux",
        rerun,
        Duration.ofMillis(1),
        outcome,
        outcome.isFailure() ? "h" : null);
  }
}
