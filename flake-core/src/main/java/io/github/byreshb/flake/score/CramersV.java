package io.github.byreshb.flake.score;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cramér's V between a boolean (failed or not) and a categorical label, used to report whether
 * failures cluster on a runner or at a time of day. It is 0 when there is no association or not
 * enough variation to measure one, and 1 when the label determines the outcome.
 */
public final class CramersV {

  private CramersV() {}

  /**
   * One observation.
   *
   * @param failed whether the run failed
   * @param label the category (runner name, hour bucket)
   */
  public record Observation(boolean failed, String label) {}

  /**
   * Computes V for the observations.
   *
   * @param observations the runs as (failed, label) pairs
   * @return V in [0, 1]; 0 when fewer than two labels or fewer than two distinct outcomes
   */
  public static double of(List<Observation> observations) {
    int n = observations.size();
    TreeSet<String> labels = new TreeSet<>();
    long failedTotal = 0;
    Map<String, Long> columnTotals = new HashMap<>();
    Map<String, Long> failedByLabel = new HashMap<>();
    for (Observation o : observations) {
      labels.add(o.label());
      columnTotals.merge(o.label(), 1L, Long::sum);
      if (o.failed()) {
        failedTotal++;
        failedByLabel.merge(o.label(), 1L, Long::sum);
      }
    }
    long passedTotal = n - failedTotal;
    if (n == 0 || labels.size() < 2 || failedTotal == 0 || passedTotal == 0) {
      return 0;
    }
    double chi2 = 0;
    for (String label : labels) {
      double column = columnTotals.get(label);
      double failedObserved = failedByLabel.getOrDefault(label, 0L);
      double failedExpected = failedTotal * column / n;
      double passedExpected = passedTotal * column / n;
      chi2 += square(failedObserved - failedExpected) / failedExpected;
      chi2 += square((column - failedObserved) - passedExpected) / passedExpected;
    }
    int minDimension = Math.min(2, labels.size());
    return Math.sqrt(chi2 / (n * (minDimension - 1)));
  }

  private static double square(double x) {
    return x * x;
  }
}
