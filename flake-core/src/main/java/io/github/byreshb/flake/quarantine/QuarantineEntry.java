package io.github.byreshb.flake.quarantine;

import io.github.byreshb.flake.model.TestId;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One quarantined test.
 *
 * @param testId the test
 * @param reason why it was quarantined, never blank
 * @param owner who is responsible for fixing or removing it, never blank
 * @param added the day it was quarantined
 * @param expires the day the quarantine stops protecting the test; must be after {@code added} and
 *     at most {@link #MAX_DAYS} days later
 * @param issueUrl tracking issue, may be null
 */
public record QuarantineEntry(
    TestId testId,
    String reason,
    String owner,
    LocalDate added,
    LocalDate expires,
    String issueUrl) {

  /** The longest a quarantine may last, in days. */
  public static final int MAX_DAYS = 90;

  /**
   * Validates the components.
   *
   * @param testId the test
   * @param reason the reason
   * @param owner the owner
   * @param added added date
   * @param expires expiry date
   * @param issueUrl issue URL or null
   */
  public QuarantineEntry {
    Objects.requireNonNull(testId, "testId");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(added, "added");
    Objects.requireNonNull(expires, "expires");
    if (reason.isBlank()) {
      throw new IllegalArgumentException(testId + ": reason must not be blank");
    }
    if (owner.isBlank()) {
      throw new IllegalArgumentException(testId + ": owner must not be blank");
    }
    if (!expires.isAfter(added)) {
      throw new IllegalArgumentException(
          testId + ": expiry " + expires + " must be after the added date " + added);
    }
    if (expires.isAfter(added.plusDays(MAX_DAYS))) {
      throw new IllegalArgumentException(
          testId + ": expiry " + expires + " is more than " + MAX_DAYS + " days after " + added);
    }
    if (issueUrl != null && issueUrl.isBlank()) {
      issueUrl = null;
    }
  }

  /**
   * Whether the quarantine has run out.
   *
   * @param today the current date
   * @return true when {@code today} is on or after the expiry date
   */
  public boolean isExpired(LocalDate today) {
    return !today.isBefore(expires);
  }

  /**
   * The tracking issue.
   *
   * @return the URL if there is one
   */
  public Optional<String> issue() {
    return Optional.ofNullable(issueUrl);
  }
}
