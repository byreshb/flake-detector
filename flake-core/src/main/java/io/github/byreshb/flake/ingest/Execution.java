package io.github.byreshb.flake.ingest;

import io.github.byreshb.flake.model.Outcome;
import java.util.Objects;

/**
 * One execution of a test case as recorded in a report, before it is tied to a build.
 *
 * @param outcome the result
 * @param failureType exception type for failures, null otherwise
 * @param failureMessage message for failures, null otherwise
 */
public record Execution(Outcome outcome, String failureType, String failureMessage) {

  /** A passed execution. */
  public static final Execution PASSED = new Execution(Outcome.PASS, null, null);

  /** A skipped execution. */
  public static final Execution SKIPPED = new Execution(Outcome.SKIPPED, null, null);

  /**
   * Validates the components.
   *
   * @param outcome the result
   * @param failureType exception type or null
   * @param failureMessage message or null
   */
  public Execution {
    Objects.requireNonNull(outcome, "outcome");
  }

  /**
   * The hash of the failure message, see {@link FailureMessages#hash(String, String)}.
   *
   * @return the hash, or null when the execution did not fail
   */
  public String failureMessageHash() {
    return outcome.isFailure() ? FailureMessages.hash(failureType, failureMessage) : null;
  }
}
