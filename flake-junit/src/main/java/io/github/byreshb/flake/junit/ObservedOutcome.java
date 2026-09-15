package io.github.byreshb.flake.junit;

import io.github.byreshb.flake.model.Outcome;
import java.time.Duration;
import java.util.Objects;

/**
 * The real outcome of a quarantined test run in {@link QuarantineMode#OBSERVE}, recorded rather
 * than allowed to fail the build.
 *
 * @param methodName the test method
 * @param duration how long it took
 * @param outcome what actually happened
 * @param failureType the thrown exception's class name, null for {@link Outcome#PASS}
 * @param failureMessage the thrown exception's message, may be null
 */
public record ObservedOutcome(
    String methodName,
    Duration duration,
    Outcome outcome,
    String failureType,
    String failureMessage) {

  /**
   * Validates the components.
   *
   * @param methodName the test method
   * @param duration how long it took
   * @param outcome what actually happened
   * @param failureType the exception type, or null
   * @param failureMessage the exception message, or null
   */
  public ObservedOutcome {
    Objects.requireNonNull(methodName, "methodName");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(outcome, "outcome");
  }
}
