package io.github.byreshb.flake.ingest;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.TestRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Reads every report under a directory (recursively) and ties the runs to one build, which is what
 * a local {@code target/surefire-reports} or the reports of one CI job amount to.
 */
public final class LocalDirectorySource implements RunSource {

  /** Report files Surefire and Failsafe write: {@code TEST-<class>.xml}. */
  public static final String DEFAULT_GLOB = "TEST-*.xml";

  private final Path directory;
  private final PathMatcher matcher;
  private final BuildRun build;
  private final String branch;
  private final String runner;
  private final JUnitXmlParser parser = new JUnitXmlParser();

  /**
   * Creates a source matching {@link #DEFAULT_GLOB}.
   *
   * @param directory root to search
   * @param build the build the reports belong to
   * @param branch branch name, empty when unknown
   * @param runner runner label, empty when unknown
   */
  public LocalDirectorySource(Path directory, BuildRun build, String branch, String runner) {
    this(directory, DEFAULT_GLOB, build, branch, runner);
  }

  /**
   * Creates a source with a custom file name glob.
   *
   * @param directory root to search
   * @param glob file name glob, matched against the file name only (for example {@code *.xml})
   * @param build the build the reports belong to
   * @param branch branch name, empty when unknown
   * @param runner runner label, empty when unknown
   */
  public LocalDirectorySource(
      Path directory, String glob, BuildRun build, String branch, String runner) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + Objects.requireNonNull(glob));
    this.build = Objects.requireNonNull(build, "build");
    this.branch = Objects.requireNonNull(branch, "branch");
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  /**
   * The report files this source will read, in path order.
   *
   * @return matching files under the directory
   * @throws UncheckedIOException when the directory cannot be walked
   */
  public List<Path> files() {
    if (!Files.isDirectory(directory)) {
      throw new UncheckedIOException(new IOException("not a directory: " + directory));
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> matcher.matches(p.getFileName()))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot walk " + directory, e);
    }
  }

  @Override
  public List<TestRun> read() {
    List<TestRun> runs = new ArrayList<>();
    for (Path file : files()) {
      for (TestCaseResult result : parser.parse(file)) {
        runs.addAll(result.toRuns(build, branch, runner));
      }
    }
    return runs;
  }
}
