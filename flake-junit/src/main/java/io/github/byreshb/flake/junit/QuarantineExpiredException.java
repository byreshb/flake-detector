package io.github.byreshb.flake.junit;

import io.github.byreshb.flake.quarantine.QuarantineEntry;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown from {@link QuarantineExtension#beforeAll} to fail a whole test class when the ledger has
 * an expired quarantine entry for one of its tests.
 */
public final class QuarantineExpiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param className the test class
   * @param expired the expired entries belonging to it, never empty
   */
  public QuarantineExpiredException(String className, List<QuarantineEntry> expired) {
    super(message(className, expired));
  }

  private static String message(String className, List<QuarantineEntry> expired) {
    String details =
        expired.stream()
            .map(
                e ->
                    e.testId()
                        + " expired on "
                        + e.expires()
                        + " (owner "
                        + e.owner()
                        + "): "
                        + e.reason())
            .collect(Collectors.joining("; "));
    return className
        + " has "
        + expired.size()
        + " expired quarantine entry(ies): "
        + details
        + ". Fix the test(s), or run 'flake quarantine add' to renew.";
  }
}
