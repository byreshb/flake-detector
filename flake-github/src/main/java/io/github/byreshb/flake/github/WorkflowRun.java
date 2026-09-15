package io.github.byreshb.flake.github;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code GET /repos/{owner}/{repo}/actions/workflows/{workflow}/runs}.
 *
 * @param id the run id
 * @param headSha the commit the run was triggered from
 * @param headBranch the branch, may be null for a run triggered from a detached head
 * @param runAttempt the attempt number, 1 for the first run
 * @param createdAt when the run started
 * @param htmlUrl link to the run on github.com
 */
public record WorkflowRun(
    long id, String headSha, String headBranch, int runAttempt, Instant createdAt, String htmlUrl) {

  /**
   * Validates the components.
   *
   * @param id the run id
   * @param headSha the commit
   * @param headBranch the branch, may be null
   * @param runAttempt the attempt number
   * @param createdAt the start time
   * @param htmlUrl link to the run
   */
  public WorkflowRun {
    Objects.requireNonNull(headSha, "headSha");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
