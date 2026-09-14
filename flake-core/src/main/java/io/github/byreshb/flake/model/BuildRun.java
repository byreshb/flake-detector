package io.github.byreshb.flake.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One execution of a CI workflow (or a local build) at a commit. Re-running a workflow on the same
 * commit yields a build with the same id and a higher attempt number.
 *
 * @param id workflow run id, or any stable identifier for a local build
 * @param commit commit SHA the build ran against
 * @param attempt attempt number, starting at 1
 * @param timestamp when the build started
 */
public record BuildRun(String id, String commit, int attempt, Instant timestamp) {

  /**
   * Validates the components.
   *
   * @param id build id
   * @param commit commit SHA
   * @param attempt attempt number
   * @param timestamp start time
   */
  public BuildRun {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(timestamp, "timestamp");
    if (id.isBlank() || commit.isBlank()) {
      throw new IllegalArgumentException("id and commit must not be blank");
    }
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be at least 1, got " + attempt);
    }
  }
}
