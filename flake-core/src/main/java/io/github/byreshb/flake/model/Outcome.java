package io.github.byreshb.flake.model;

/** The result of one execution of one test. */
public enum Outcome {
  /** The test ran and passed. */
  PASS,
  /** The test ran and an assertion failed. */
  FAIL,
  /** The test ran and threw an unexpected exception. */
  ERROR,
  /** The test did not run. */
  SKIPPED;

  /**
   * Whether this outcome counts as a failure for scoring and gating purposes.
   *
   * @return true for {@link #FAIL} and {@link #ERROR}
   */
  public boolean isFailure() {
    return this == FAIL || this == ERROR;
  }
}
