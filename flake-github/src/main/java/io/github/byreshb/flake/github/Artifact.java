package io.github.byreshb.flake.github;

import java.util.Objects;

/**
 * One row of {@code GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts}.
 *
 * @param id the artifact id
 * @param name the artifact name, as given to {@code actions/upload-artifact}
 * @param archiveDownloadUrl API URL that serves the artifact as a zip (requires authentication)
 * @param expired whether GitHub has already deleted the artifact's content
 */
public record Artifact(long id, String name, String archiveDownloadUrl, boolean expired) {

  /**
   * Validates the components.
   *
   * @param id the artifact id
   * @param name the artifact name
   * @param archiveDownloadUrl the download URL
   * @param expired whether the artifact has expired
   */
  public Artifact {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(archiveDownloadUrl, "archiveDownloadUrl");
  }
}
