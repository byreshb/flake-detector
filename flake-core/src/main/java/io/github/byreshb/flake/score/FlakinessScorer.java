package io.github.byreshb.flake.score;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.store.RunStore;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes a {@link FlakeScore} from the run history of a test. The formulas are documented in
 * {@code docs/scoring.md} and pinned by {@code conformance/scoring.json}.
 *
 * <p>Skipped runs are ignored. Runs are ordered by build timestamp, attempt and rerun index, then
 * grouped by commit; every statistic that compares runs only compares runs on the same commit,
 * because a change of outcome between commits may be a real regression or fix.
 */
public final class FlakinessScorer {

  private static final Comparator<TestRun> CHRONOLOGICAL =
      Comparator.comparing(TestRun::timestamp)
          .thenComparingInt(r -> r.build().attempt())
          .thenComparingInt(TestRun::rerun);

  /** Ranking: highest score first, then more runs first, then by test id. */
  public static final Comparator<FlakeScore> RANKING =
      Comparator.comparingDouble(FlakeScore::score)
          .reversed()
          .thenComparing(Comparator.comparingInt(FlakeScore::runs).reversed())
          .thenComparing(FlakeScore::testId);

  private final Weights weights;

  /** Creates a scorer with the default weights. */
  public FlakinessScorer() {
    this(Weights.DEFAULT);
  }

  /**
   * Creates a scorer.
   *
   * @param weights component weights
   */
  public FlakinessScorer(Weights weights) {
    this.weights = Objects.requireNonNull(weights, "weights");
  }

  /**
   * Scores every test in the store.
   *
   * @param store the run history
   * @return one score per test, in {@link #RANKING} order
   */
  public List<FlakeScore> scoreAll(RunStore store) {
    Map<TestId, List<TestRun>> byTest = new LinkedHashMap<>();
    for (TestRun run : store.allRuns()) {
      byTest.computeIfAbsent(run.testId(), id -> new ArrayList<>()).add(run);
    }
    List<FlakeScore> scores = new ArrayList<>();
    byTest.forEach((id, runs) -> scores.add(score(id, runs)));
    scores.sort(RANKING);
    return scores;
  }

  /**
   * Scores one test.
   *
   * @param testId the test
   * @param history its runs, in any order; runs of other tests are ignored
   * @return the score
   */
  public FlakeScore score(TestId testId, List<TestRun> history) {
    List<TestRun> runs =
        history.stream()
            .filter(r -> r.testId().equals(testId))
            .filter(r -> r.outcome() != Outcome.SKIPPED)
            .sorted(CHRONOLOGICAL)
            .toList();

    Map<String, List<TestRun>> byCommit = new LinkedHashMap<>();
    for (TestRun run : runs) {
      byCommit.computeIfAbsent(run.commit(), c -> new ArrayList<>()).add(run);
    }

    int pairs = 0;
    int flips = 0;
    int failures = 0;
    int recovered = 0;
    for (List<TestRun> group : byCommit.values()) {
      for (int i = 1; i < group.size(); i++) {
        pairs++;
        if (group.get(i).failed() != group.get(i - 1).failed()) {
          flips++;
        }
      }
      for (int i = 0; i < group.size(); i++) {
        if (group.get(i).failed()) {
          failures++;
          if (passesAfter(group, i)) {
            recovered++;
          }
        }
      }
    }

    Map<String, Integer> messages = new HashMap<>();
    for (TestRun run : runs) {
      if (run.failed()) {
        messages.merge(String.valueOf(run.failureMessageHash()), 1, Integer::sum);
      }
    }
    double entropy = 0;
    if (failures > 1) {
      double h = 0;
      for (int count : messages.values()) {
        double p = (double) count / failures;
        h -= p * log2(p);
      }
      entropy = h / log2(failures);
    }
    double entropyComponent = failures == 0 ? 0 : entropy * (1 - 1.0 / failures);

    Wilson flipInterval = Wilson.of(flips, pairs);
    Wilson recoveryInterval = Wilson.of(recovered, failures);
    double runnerCorrelation =
        CramersV.of(
            runs.stream().map(r -> new CramersV.Observation(r.failed(), r.runner())).toList());
    double hourCorrelation =
        CramersV.of(
            runs.stream().map(r -> new CramersV.Observation(r.failed(), hourBucket(r))).toList());

    double score =
        weights.rerunRecovery() * recoveryInterval.lower()
            + weights.flipRate() * flipInterval.lower()
            + weights.entropy() * entropyComponent;

    return new FlakeScore(
        testId,
        runs.size(),
        failures,
        pairs,
        flips,
        pairs == 0 ? 0 : (double) flips / pairs,
        flipInterval,
        recovered,
        failures == 0 ? 0 : (double) recovered / failures,
        recoveryInterval,
        messages.size(),
        entropy,
        entropyComponent,
        runnerCorrelation,
        hourCorrelation,
        weights,
        score);
  }

  private static boolean passesAfter(List<TestRun> group, int index) {
    for (int j = index + 1; j < group.size(); j++) {
      if (!group.get(j).failed()) {
        return true;
      }
    }
    return false;
  }

  private static String hourBucket(TestRun run) {
    int hour = run.timestamp().atZone(ZoneOffset.UTC).getHour();
    return String.valueOf(hour / 4);
  }

  private static double log2(double x) {
    return Math.log(x) / Math.log(2);
  }
}
