package io.github.byreshb.flake.score;

/**
 * Weights of the three scored components. They need not sum to one, but the defaults do so that the
 * score is bounded by 1.
 *
 * @param rerunRecovery weight of the rerun-recovery rate (lower Wilson bound)
 * @param flipRate weight of the flip rate (lower Wilson bound)
 * @param entropy weight of the failure-message entropy component
 */
public record Weights(double rerunRecovery, double flipRate, double entropy) {

  /** The default weights: 0.5, 0.3 and 0.2. */
  public static final Weights DEFAULT = new Weights(0.5, 0.3, 0.2);

  /**
   * Validates the weights.
   *
   * @param rerunRecovery weight of the rerun-recovery rate
   * @param flipRate weight of the flip rate
   * @param entropy weight of the entropy component
   */
  public Weights {
    if (rerunRecovery < 0 || flipRate < 0 || entropy < 0) {
      throw new IllegalArgumentException("weights must not be negative");
    }
  }
}
