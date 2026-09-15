package io.github.byreshb.flake.report;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.score.FlakeScore;
import io.github.byreshb.flake.score.FlakinessScorer;
import io.github.byreshb.flake.store.RunStore;
import java.util.ArrayList;
import java.util.List;

/** Builds {@link ReportEntry} lists from a run store, ready to hand to a report renderer. */
public final class Reports {

  /** Default number of trend points kept per test, oldest dropped first. */
  public static final int DEFAULT_TREND_POINTS = 30;

  private Reports() {}

  /**
   * Scores every test in the store and attaches its recent trend.
   *
   * @param store the run history
   * @param scorer how to score each test
   * @param trendPoints how many of the most recent non-skipped runs to keep per test
   * @return one entry per test, in the scorer's ranking order
   */
  public static List<ReportEntry> build(RunStore store, FlakinessScorer scorer, int trendPoints) {
    List<FlakeScore> scores = scorer.scoreAll(store);
    List<ReportEntry> entries = new ArrayList<>(scores.size());
    for (FlakeScore score : scores) {
      List<TestRun> runs = store.runsOf(score.testId());
      List<Outcome> trend =
          runs.stream()
              .map(TestRun::outcome)
              .filter(outcome -> outcome != Outcome.SKIPPED)
              .toList();
      if (trend.size() > trendPoints) {
        trend = trend.subList(trend.size() - trendPoints, trend.size());
      }
      entries.add(new ReportEntry(score, trend));
    }
    return entries;
  }
}
