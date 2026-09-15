package io.github.byreshb.flake.junit;

/** How {@link QuarantineExtension} treats a quarantined, unexpired test. */
public enum QuarantineMode {

  /** Quarantined tests are not run; JUnit reports them as disabled. */
  SKIP,

  /**
   * Quarantined tests run normally, but a failure does not fail the build: it is caught, recorded
   * with its real outcome in a separate report, and not rethrown.
   */
  OBSERVE
}
