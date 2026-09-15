package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.github.GitHubArtifactsSource;
import io.github.byreshb.flake.github.GitHubClient;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code flake ingest github}: read reports from a GitHub Actions workflow's run artifacts. */
@Command(
    name = "github",
    mixinStandardHelpOptions = true,
    description =
        "Read Surefire/Failsafe reports from a GitHub Actions workflow's artifacts into the run"
            + " history.")
public final class IngestGithubCommand implements Callable<Integer> {

  @Option(
      names = "--repo",
      required = true,
      paramLabel = "OWNER/NAME",
      description = "The repository, e.g. byreshb/flake-detector.")
  private String repo;

  @Option(
      names = "--workflow",
      required = true,
      paramLabel = "FILE-OR-ID",
      description = "Workflow file name (e.g. ci.yml) or numeric workflow id.")
  private String workflow;

  @Option(
      names = "--runs",
      paramLabel = "N",
      defaultValue = "50",
      description =
          "How many of the most recent workflow runs to read (default: ${DEFAULT-VALUE}).")
  private int maxRuns;

  @Option(
      names = "--artifact",
      paramLabel = "GLOB",
      defaultValue = GitHubArtifactsSource.DEFAULT_ARTIFACT_GLOB,
      description = "Artifact name pattern (default: ${DEFAULT-VALUE}).")
  private String artifactGlob;

  @Option(
      names = "--runner",
      paramLabel = "LABEL",
      defaultValue = GitHubArtifactsSource.DEFAULT_RUNNER,
      description = "Runner label recorded for these runs (default: ${DEFAULT-VALUE}).")
  private String runnerLabel;

  @Mixin private StoreOptions store;

  @Spec private CommandSpec spec;

  private final Map<String, String> env;
  private final GitHubClient clientOverride;

  /** Creates the command reading {@code GITHUB_TOKEN} from the real environment. */
  public IngestGithubCommand() {
    this(System.getenv(), null);
  }

  /**
   * Creates the command with an explicit environment and, optionally, a client to use instead of
   * building one from the token (for pointing tests at something other than the real API).
   *
   * @param env where to read {@code GITHUB_TOKEN} from
   * @param clientOverride client to use, or null to build one with {@link GitHubClient#create}
   */
  IngestGithubCommand(Map<String, String> env, GitHubClient clientOverride) {
    this.env = env;
    this.clientOverride = clientOverride;
  }

  @Override
  public Integer call() {
    String[] ownerAndName = repo.split("/", 2);
    if (ownerAndName.length != 2 || ownerAndName[0].isBlank() || ownerAndName[1].isBlank()) {
      spec.commandLine().getErr().printf("--repo must be OWNER/NAME, got '%s'%n", repo);
      return 1;
    }
    String token = env.get("GITHUB_TOKEN");
    if (token == null || token.isBlank()) {
      spec.commandLine()
          .getErr()
          .println("GITHUB_TOKEN is not set; it is required to download workflow artifacts.");
      return 1;
    }
    GitHubClient client = clientOverride != null ? clientOverride : GitHubClient.create(token);
    GitHubArtifactsSource source =
        new GitHubArtifactsSource(
            client, ownerAndName[0], ownerAndName[1], workflow, maxRuns, artifactGlob, runnerLabel);
    List<TestRun> runs = source.read();
    try (SqliteRunStore db = store.open()) {
      int added = db.record(runs);
      spec.commandLine()
          .getOut()
          .printf(
              "Ingested %d run(s) from up to %d run(s) of %s (%s), %d new, into %s%n",
              runs.size(), maxRuns, workflow, repo, added, store.database());
      spec.commandLine()
          .getOut()
          .printf("History now holds %d run(s) over %d build(s)%n", db.runCount(), db.buildCount());
    }
    return 0;
  }
}
