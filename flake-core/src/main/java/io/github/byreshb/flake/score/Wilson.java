package io.github.byreshb.flake.score;

/**
 * Wilson score interval for a binomial proportion, the interval that keeps a test with 1 flip in 1
 * pair from ranking above a test with 30 flips in 300 pairs.
 *
 * @param lower lower bound of the interval, 0 when there are no trials
 * @param upper upper bound of the interval, 0 when there are no trials
 */
public record Wilson(double lower, double upper) {

  /** z for a 95% interval. */
  public static final double Z = 1.96;

  /** The empty interval used when there are no trials. */
  public static final Wilson NONE = new Wilson(0, 0);

  /**
   * Computes the 95% interval.
   *
   * @param successes number of successes, at most {@code trials}
   * @param trials number of trials
   * @return the interval, or {@link #NONE} when {@code trials} is 0
   */
  public static Wilson of(long successes, long trials) {
    if (trials == 0) {
      return NONE;
    }
    if (successes < 0 || successes > trials) {
      throw new IllegalArgumentException(successes + " successes in " + trials + " trials");
    }
    double n = trials;
    double p = successes / n;
    double z2 = Z * Z;
    double denominator = 1 + z2 / n;
    double centre = p + z2 / (2 * n);
    double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n));
    return new Wilson(clamp((centre - half) / denominator), clamp((centre + half) / denominator));
  }

  private static double clamp(double value) {
    return Math.min(1, Math.max(0, value));
  }
}
