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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportsTest {

  private static final TestId FLAKY = TestId.parse("com.acme.CheckoutTest#appliesCoupon");

  private static TestRun run(int day, int rerun, Outcome outcome) {
    BuildRun build =
        new BuildRun(
            "b" + day,
            "c" + day,
            1,
            Instant.parse("2026-01-01T00:00:00Z").plus(Duration.ofDays(day)));
    return new TestRun(FLAKY, build, "main", "linux", rerun, Duration.ofMillis(1), outcome, null);
  }

  @Test
  void buildsOneEntryPerTestWithATrendCappedToTheLimit() {
    List<TestRun> runs = new ArrayList<>();
    for (int day = 0; day < 5; day++) {
      runs.add(run(day, 0, day % 2 == 0 ? Outcome.PASS : Outcome.FAIL));
    }
    runs.add(run(4, 1, Outcome.SKIPPED));

    try (SqliteRunStore store = SqliteRunStore.inMemory()) {
      store.record(runs);

      List<ReportEntry> entries = Reports.build(store, new FlakinessScorer(), 3);

      assertThat(entries).hasSize(1);
      ReportEntry entry = entries.get(0);
      assertThat(entry.score().testId()).isEqualTo(FLAKY);
      // 5 non-skipped runs (PASS, FAIL, PASS, FAIL, PASS), capped to the most recent 3.
      assertThat(entry.trend()).containsExactly(Outcome.PASS, Outcome.FAIL, Outcome.PASS);
    }
  }

  @Test
  void reportEntryCopiesTheTrendDefensively() {
    List<Outcome> trend = new ArrayList<>(List.of(Outcome.PASS));
    ReportEntry entry = new ReportEntry(new FlakinessScorer().score(FLAKY, List.of()), trend);
    trend.add(Outcome.FAIL);

    assertThat(entry.trend()).containsExactly(Outcome.PASS);
  }
}
