package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.ingest.JUnitXmlParser;
import io.github.byreshb.flake.ingest.LocalDirectorySource;
import io.github.byreshb.flake.ingest.TestCaseResult;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.quarantine.QuarantineEntry;
import io.github.byreshb.flake.quarantine.QuarantineLedger;
import io.github.byreshb.flake.score.FlakeScore;
import io.github.byreshb.flake.score.FlakinessScorer;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.io.PrintWriter;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code flake gate}: reads the reports of the current build and exits non-zero only when a test
 * failed that is neither quarantined (and unexpired) nor above the flake threshold. Passes, and
 * fails that are excused, are printed with the reason; every excuse is explicit so a green gate is
 * never a mystery.
 */
@Command(
    name = "gate",
    mixinStandardHelpOptions = true,
    description = "Fail the build only for failures that are not quarantined and not known-flaky.")
public final class GateCommand implements Callable<Integer> {

  @Option(
      names = "--reports",
      required = true,
      paramLabel = "DIR",
      description = "Directory searched recursively for the current build's report files.")
  private Path reports;

  @Option(
      names = "--glob",
      paramLabel = "GLOB",
      defaultValue = LocalDirectorySource.DEFAULT_GLOB,
      description = "File name pattern of the reports (default: ${DEFAULT-VALUE}).")
  private String glob;

  @Option(
      names = "--threshold",
      paramLabel = "SCORE",
      defaultValue = "0.3",
      description = "Flakiness score above which a failure is excused (default: ${DEFAULT-VALUE}).")
  private double threshold;

  @Mixin private StoreOptions store;

  @Mixin private LedgerOptions ledger;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    List<Path> files = matchingFiles();
    if (files.isEmpty()) {
      spec.commandLine().getErr().printf("No files matching %s under %s%n", glob, reports);
      return 1;
    }

    JUnitXmlParser parser = new JUnitXmlParser();
    List<TestId> failedTests = new ArrayList<>();
    for (Path file : files) {
      for (TestCaseResult result : parser.parse(file)) {
        if (result.last().outcome().isFailure()) {
          failedTests.add(result.testId());
        }
      }
    }

    PrintWriter out = spec.commandLine().getOut();
    if (failedTests.isEmpty()) {
      out.printf("No failures in %d report file(s); gate passes.%n", files.size());
      return 0;
    }

    QuarantineLedger currentLedger = ledger.load();
    LocalDate today = LocalDate.now();
    FlakinessScorer scorer = new FlakinessScorer();
    List<TestId> mustFix = new ArrayList<>();
    try (SqliteRunStore db = store.open()) {
      for (TestId testId : failedTests) {
        var quarantine = currentLedger.find(testId);
        if (quarantine.isPresent() && !quarantine.get().isExpired(today)) {
          QuarantineEntry entry = quarantine.get();
          out.printf(
              "SKIP  %s: quarantined by %s until %s (%s)%n",
              testId, entry.owner(), entry.expires(), entry.reason());
          continue;
        }
        FlakeScore score = scorer.score(testId, db.runsOf(testId));
        if (score.score() > threshold) {
          out.printf(
              "SKIP  %s: flakiness score %.3f > threshold %.3f (%d run(s) in history)%n",
              testId, score.score(), threshold, score.runs());
          continue;
        }
        if (quarantine.isPresent()) {
          out.printf(
              "FAIL  %s: quarantine expired on %s; treated as a normal failure%n",
              testId, quarantine.get().expires());
        } else {
          out.printf(
              "FAIL  %s: not quarantined, flakiness score %.3f <= threshold %.3f%n",
              testId, score.score(), threshold);
        }
        mustFix.add(testId);
      }
    }

    out.printf(
        "%n%d failure(s), %d excused, %d must be fixed.%n",
        failedTests.size(), failedTests.size() - mustFix.size(), mustFix.size());
    return mustFix.isEmpty() ? 0 : 1;
  }

  private List<Path> matchingFiles() {
    if (!Files.isDirectory(reports)) {
      return List.of();
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
    try (Stream<Path> walk = Files.walk(reports)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> matcher.matches(p.getFileName()))
          .sorted()
          .toList();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("cannot walk " + reports, e);
    }
  }
}
