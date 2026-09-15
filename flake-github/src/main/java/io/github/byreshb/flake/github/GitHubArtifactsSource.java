package io.github.byreshb.flake.github;

import io.github.byreshb.flake.ingest.JUnitXmlParser;
import io.github.byreshb.flake.ingest.ReportFormatException;
import io.github.byreshb.flake.ingest.RunSource;
import io.github.byreshb.flake.ingest.TestCaseResult;
import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.TestRun;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads Surefire/Failsafe reports out of the artifacts of a GitHub Actions workflow's recent runs.
 * Each matching artifact is downloaded once, unzipped in memory, and every {@code .xml} entry in it
 * is handed to {@link JUnitXmlParser}; entries that are not JUnit reports are skipped.
 */
public final class GitHubArtifactsSource implements RunSource {

  /** Default artifact name glob: anything with "surefire" or "failsafe" in the name. */
  public static final String DEFAULT_ARTIFACT_GLOB = "*{surefire,failsafe}*";

  /** Runner label recorded for runs read this way, since the API does not expose the label. */
  public static final String DEFAULT_RUNNER = "github-actions";

  private static final int PAGE_SIZE = 100;

  private final GitHubClient client;
  private final String owner;
  private final String repo;
  private final String workflow;
  private final int maxRuns;
  private final PathMatcher artifactMatcher;
  private final String runnerLabel;
  private final JUnitXmlParser parser = new JUnitXmlParser();

  /**
   * Creates a source with the default artifact glob and runner label.
   *
   * @param client the API client
   * @param owner repository owner
   * @param repo repository name
   * @param workflow workflow file name (e.g. {@code ci.yml}) or numeric id
   * @param maxRuns how many of the most recent workflow runs to read
   */
  public GitHubArtifactsSource(
      GitHubClient client, String owner, String repo, String workflow, int maxRuns) {
    this(client, owner, repo, workflow, maxRuns, DEFAULT_ARTIFACT_GLOB, DEFAULT_RUNNER);
  }

  /**
   * Creates a source.
   *
   * @param client the API client
   * @param owner repository owner
   * @param repo repository name
   * @param workflow workflow file name (e.g. {@code ci.yml}) or numeric id
   * @param maxRuns how many of the most recent workflow runs to read
   * @param artifactGlob artifact name pattern, matched case-sensitively against the artifact name
   * @param runnerLabel runner label recorded for every run read this way
   */
  public GitHubArtifactsSource(
      GitHubClient client,
      String owner,
      String repo,
      String workflow,
      int maxRuns,
      String artifactGlob,
      String runnerLabel) {
    this.client = Objects.requireNonNull(client, "client");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.repo = Objects.requireNonNull(repo, "repo");
    this.workflow = Objects.requireNonNull(workflow, "workflow");
    this.maxRuns = maxRuns;
    this.artifactMatcher =
        FileSystems.getDefault().getPathMatcher("glob:" + Objects.requireNonNull(artifactGlob));
    this.runnerLabel = Objects.requireNonNull(runnerLabel, "runnerLabel");
  }

  /**
   * The workflow runs this source will read, most recent first, without downloading anything.
   *
   * @return up to {@code maxRuns} runs
   */
  public List<WorkflowRun> runs() {
    List<WorkflowRun> runs = new ArrayList<>();
    for (int page = 1; runs.size() < maxRuns; page++) {
      List<Object> items =
          client.listWorkflowRunsPage(
              owner, repo, workflow, page, Math.min(PAGE_SIZE, maxRuns - runs.size()));
      if (items.isEmpty()) {
        break;
      }
      for (Object item : items) {
        runs.add(toWorkflowRun(asMap(item)));
        if (runs.size() >= maxRuns) {
          break;
        }
      }
    }
    return runs;
  }

  /**
   * The artifacts of one run that match this source's glob.
   *
   * @param runId the workflow run id
   * @return matching, non-expired artifacts
   */
  public List<Artifact> matchingArtifacts(long runId) {
    List<Artifact> matches = new ArrayList<>();
    for (Object item : client.listArtifacts(owner, repo, runId)) {
      Artifact artifact = toArtifact(asMap(item));
      if (!artifact.expired() && artifactMatcher.matches(Path.of(artifact.name()))) {
        matches.add(artifact);
      }
    }
    return matches;
  }

  @Override
  public List<TestRun> read() {
    List<TestRun> runs = new ArrayList<>();
    for (WorkflowRun run : runs()) {
      BuildRun build =
          new BuildRun(String.valueOf(run.id()), run.headSha(), run.runAttempt(), run.createdAt());
      String branch = run.headBranch() == null ? "" : run.headBranch();
      for (Artifact artifact : matchingArtifacts(run.id())) {
        for (Map.Entry<String, byte[]> entry :
            unzip(client.downloadArtifact(artifact.archiveDownloadUrl())).entrySet()) {
          if (!entry.getKey().toLowerCase(Locale.ROOT).endsWith(".xml")) {
            continue;
          }
          try {
            for (TestCaseResult result : parser.parse(new ByteArrayInputStream(entry.getValue()))) {
              runs.addAll(result.toRuns(build, branch, runnerLabel));
            }
          } catch (ReportFormatException notAReport) {
            // The artifact may contain non-report files (logs, screenshots); skip them.
          }
        }
      }
    }
    return runs;
  }

  private static WorkflowRun toWorkflowRun(Map<String, Object> run) {
    return new WorkflowRun(
        number(run.get("id")).longValue(),
        string(run.get("head_sha")),
        (String) run.get("head_branch"),
        run.containsKey("run_attempt") ? number(run.get("run_attempt")).intValue() : 1,
        Instant.parse(string(run.get("created_at"))),
        (String) run.get("html_url"));
  }

  private static Artifact toArtifact(Map<String, Object> artifact) {
    return new Artifact(
        number(artifact.get("id")).longValue(),
        string(artifact.get("name")),
        string(artifact.get("archive_download_url")),
        Boolean.TRUE.equals(artifact.get("expired")));
  }

  private static Map<String, byte[]> unzip(byte[] zip) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (!entry.isDirectory()) {
          files.put(entry.getName(), in.readAllBytes());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read artifact zip", e);
    }
    return files;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static Number number(Object value) {
    if (value instanceof Number number) {
      return number;
    }
    throw new GitHubApiException(200, "expected a number, got " + value, null);
  }

  private static String string(Object value) {
    if (value instanceof String text) {
      return text;
    }
    throw new GitHubApiException(200, "expected a string, got " + value, null);
  }
}
