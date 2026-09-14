package io.github.byreshb.flake.score;

import io.github.byreshb.flake.model.TestId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The flakiness of one test, with every component that went into the score.
 *
 * @param testId the test
 * @param runs number of runs considered (skipped runs are not)
 * @param failures number of failed runs
 * @param flipPairs number of consecutive same-commit run pairs
 * @param flips number of those pairs whose outcomes differ
 * @param flipRate {@code flips / flipPairs}, 0 without pairs
 * @param flipRateInterval Wilson interval of the flip rate
 * @param recoveredFailures failures followed by a pass on the same commit
 * @param rerunRecoveryRate {@code recoveredFailures / failures}, 0 without failures
 * @param rerunRecoveryInterval Wilson interval of the rerun-recovery rate
 * @param distinctMessages number of distinct failure message hashes
 * @param entropy normalised Shannon entropy of the failure messages, in [0, 1]
 * @param entropyComponent entropy shrunk by the number of failures, {@code entropy * (1 - 1 /
 *     failures)}
 * @param runnerCorrelation Cramér's V between failing and the runner label
 * @param hourCorrelation Cramér's V between failing and the four-hour bucket of the day (UTC)
 * @param weights the weights used
 * @param score the weighted total in [0, 1]
 */
public record FlakeScore(
    TestId testId,
    int runs,
    int failures,
    int flipPairs,
    int flips,
    double flipRate,
    Wilson flipRateInterval,
    int recoveredFailures,
    double rerunRecoveryRate,
    Wilson rerunRecoveryInterval,
    int distinctMessages,
    double entropy,
    double entropyComponent,
    double runnerCorrelation,
    double hourCorrelation,
    Weights weights,
    double score) {

  /**
   * Validates the components.
   *
   * @param testId the test
   * @param runs runs considered
   * @param failures failed runs
   * @param flipPairs consecutive pairs
   * @param flips flipping pairs
   * @param flipRate flip rate
   * @param flipRateInterval its interval
   * @param recoveredFailures recovered failures
   * @param rerunRecoveryRate recovery rate
   * @param rerunRecoveryInterval its interval
   * @param distinctMessages distinct message hashes
   * @param entropy normalised entropy
   * @param entropyComponent shrunk entropy
   * @param runnerCorrelation runner correlation
   * @param hourCorrelation hour correlation
   * @param weights the weights
   * @param score the total
   */
  public FlakeScore {
    Objects.requireNonNull(testId, "testId");
    Objects.requireNonNull(flipRateInterval, "flipRateInterval");
    Objects.requireNonNull(rerunRecoveryInterval, "rerunRecoveryInterval");
    Objects.requireNonNull(weights, "weights");
  }

  /**
   * One line per component with its value, weight and contribution to the score, followed by the
   * reported-only correlations. Suitable for a terminal, a PR comment or an issue.
   *
   * @return the explanation
   */
  public String explain() {
    Component recovery =
        new Component(
            "rerun recovery",
            String.format(
                Locale.ROOT,
                "%d of %d failure(s) passed on a retry of the same commit; rate %.2f, 95%% interval"
                    + " [%.2f, %.2f]",
                recoveredFailures,
                failures,
                rerunRecoveryRate,
                rerunRecoveryInterval.lower(),
                rerunRecoveryInterval.upper()),
            rerunRecoveryInterval.lower(),
            weights.rerunRecovery());
    Component flip =
        new Component(
            "flip rate",
            String.format(
                Locale.ROOT,
                "%d of %d consecutive same-commit pair(s) changed outcome; rate %.2f, 95%% interval"
                    + " [%.2f, %.2f]",
                flips,
                flipPairs,
                flipRate,
                flipRateInterval.lower(),
                flipRateInterval.upper()),
            flipRateInterval.lower(),
            weights.flipRate());
    Component messages =
        new Component(
            "message entropy",
            String.format(
                Locale.ROOT,
                "%d distinct message(s) over %d failure(s); entropy %.2f, shrunk to %.2f",
                distinctMessages,
                failures,
                entropy,
                entropyComponent),
            entropyComponent,
            weights.entropy());
    StringBuilder text = new StringBuilder();
    text.append(String.format(Locale.ROOT, "%s: score %.3f over %d run(s)%n", testId, score, runs));
    for (Component component : List.of(recovery, flip, messages)) {
      text.append(
          String.format(
              Locale.ROOT,
              "  %-16s %.3f x %.1f = %.3f  (%s)%n",
              component.name(),
              component.value(),
              component.weight(),
              component.contribution(),
              component.detail()));
    }
    text.append(
        String.format(
            Locale.ROOT,
            "  reported only:   runner correlation %.2f, hour-of-day correlation %.2f%n",
            runnerCorrelation,
            hourCorrelation));
    return text.toString();
  }

  /**
   * One scored component.
   *
   * @param name display name
   * @param detail how the value was obtained
   * @param value the value fed into the score
   * @param weight its weight
   */
  public record Component(String name, String detail, double value, double weight) {

    /**
     * The component's share of the score.
     *
     * @return {@code value * weight}
     */
    public double contribution() {
      return value * weight;
    }
  }
}
