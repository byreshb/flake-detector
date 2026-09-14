package io.github.byreshb.flake.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One execution of one test inside a build. When Surefire reruns a failing test, every execution
 * becomes its own {@code TestRun} with an increasing {@link #rerun()} index, so a test that failed
 * twice and then passed contributes three runs.
 *
 * @param testId the test
 * @param build the build the test ran in
 * @param branch branch the build ran on, never null (use an empty string when unknown)
 * @param runner label of the machine or runner group, never null (empty when unknown)
 * @param rerun zero for the first execution in a build, then 1, 2, ... for Surefire reruns
 * @param duration wall-clock time of the execution
 * @param outcome the result
 * @param failureMessageHash hash of the normalised failure message, or null when the outcome is not
 *     a failure
 */
public record TestRun(
    TestId testId,
    BuildRun build,
    String branch,
    String runner,
    int rerun,
    Duration duration,
    Outcome outcome,
    String failureMessageHash) {

  /**
   * Validates the components.
   *
   * @param testId the test
   * @param build the build
   * @param branch branch name
   * @param runner runner label
   * @param rerun rerun index
   * @param duration execution time
   * @param outcome the result
   * @param failureMessageHash failure message hash or null
   */
  public TestRun {
    Objects.requireNonNull(testId, "testId");
    Objects.requireNonNull(build, "build");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(runner, "runner");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(outcome, "outcome");
    if (rerun < 0) {
      throw new IllegalArgumentException("rerun must not be negative, got " + rerun);
    }
    if (!outcome.isFailure() && failureMessageHash != null) {
      throw new IllegalArgumentException("only failures carry a failure message hash");
    }
  }

  /**
   * The commit the run happened on, taken from the build.
   *
   * @return commit SHA
   */
  public String commit() {
    return build.commit();
  }

  /**
   * When the run happened, taken from the build.
   *
   * @return build start time
   */
  public Instant timestamp() {
    return build.timestamp();
  }

  /**
   * Whether the outcome is a failure.
   *
   * @return true for FAIL and ERROR
   */
  public boolean failed() {
    return outcome.isFailure();
  }
}
