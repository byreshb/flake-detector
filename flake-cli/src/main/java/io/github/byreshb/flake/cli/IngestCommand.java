package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.ingest.LocalDirectorySource;
import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code flake ingest <dir>}: read a directory of reports into the run history. */
@Command(
    name = "ingest",
    mixinStandardHelpOptions = true,
    description = "Read Surefire/Failsafe XML reports from a directory into the run history.",
    subcommands = {IngestGithubCommand.class})
public final class IngestCommand implements Callable<Integer> {

  // arity 0..1, not required: a bare "flake ingest github ..." must resolve to the github
  // subcommand below rather than being swallowed as the value of this positional parameter.
  @Parameters(
      index = "0",
      arity = "0..1",
      paramLabel = "DIR",
      description = "Directory searched recursively for report files.")
  private Path directory;

  @Option(
      names = "--glob",
      paramLabel = "GLOB",
      defaultValue = LocalDirectorySource.DEFAULT_GLOB,
      description = "File name pattern of the reports (default: ${DEFAULT-VALUE}).")
  private String glob;

  @Option(
      names = "--commit",
      paramLabel = "SHA",
      description = "Commit the reports belong to (default: GITHUB_SHA, then git HEAD).")
  private String commit;

  @Option(
      names = "--branch",
      paramLabel = "NAME",
      description = "Branch (default: GITHUB_REF_NAME, then git).")
  private String branch;

  @Option(
      names = "--runner",
      paramLabel = "LABEL",
      description = "Runner label (default: RUNNER_NAME).")
  private String runner;

  @Option(
      names = "--build-id",
      paramLabel = "ID",
      description = "Build id (default: GITHUB_RUN_ID, then a timestamp).")
  private String buildId;

  @Option(
      names = "--attempt",
      paramLabel = "N",
      description = "Build attempt (default: GITHUB_RUN_ATTEMPT, then 1).")
  private Integer attempt;

  @Mixin private StoreOptions store;

  @Spec private CommandSpec spec;

  private final BuildContext context;

  /** Creates the command with the real environment. */
  public IngestCommand() {
    this(BuildContext.fromEnvironment());
  }

  /**
   * Creates the command with a specific build context.
   *
   * @param context how to resolve commit, branch and build id
   */
  public IngestCommand(BuildContext context) {
    this.context = context;
  }

  @Override
  public Integer call() {
    if (directory == null) {
      spec.commandLine()
          .getErr()
          .println(
              "Missing required parameter: 'DIR' (or run 'flake ingest github' to ingest from"
                  + " GitHub Actions)");
      spec.commandLine().usage(spec.commandLine().getErr());
      return ExitCode.USAGE;
    }
    BuildRun build = context.build(buildId, commit, attempt);
    LocalDirectorySource source =
        new LocalDirectorySource(
            directory, glob, build, context.branch(branch), context.runner(runner));
    List<Path> files = source.files();
    if (files.isEmpty()) {
      spec.commandLine().getErr().printf("No files matching %s under %s%n", glob, directory);
      return 1;
    }
    List<TestRun> runs = source.read();
    try (SqliteRunStore db = store.open()) {
      int added = db.record(runs);
      spec.commandLine()
          .getOut()
          .printf(
              "Ingested %d file(s), %d run(s), %d new, into %s (build %s attempt %d, commit %s)%n",
              files.size(),
              runs.size(),
              added,
              store.database(),
              build.id(),
              build.attempt(),
              build.commit());
      spec.commandLine()
          .getOut()
          .printf("History now holds %d run(s) over %d build(s)%n", db.runCount(), db.buildCount());
    }
    return 0;
  }
}
