package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.score.FlakeScore;
import io.github.byreshb.flake.score.FlakinessScorer;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code flake score}: rank every test in the history by flakiness. */
@Command(
    name = "score",
    mixinStandardHelpOptions = true,
    description = "Rank the tests in the run history by flakiness score.")
public final class ScoreCommand implements Callable<Integer> {

  /** Output formats. */
  public enum Format {
    /** A Markdown table followed by explanations of the top suspects. */
    MD,
    /** A JSON array with every component of every score. */
    JSON
  }

  @Option(
      names = "--top",
      paramLabel = "N",
      defaultValue = "20",
      description = "How many tests to list (default: ${DEFAULT-VALUE}; 0 for all).")
  private int top;

  @Option(
      names = "--format",
      paramLabel = "FORMAT",
      defaultValue = "md",
      description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
  private Format format;

  @Option(
      names = "--explain",
      paramLabel = "N",
      defaultValue = "3",
      description =
          "How many of the top tests to explain in Markdown output (default: ${DEFAULT-VALUE}).")
  private int explain;

  @Mixin private StoreOptions store;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    List<FlakeScore> scores;
    try (SqliteRunStore db = store.open()) {
      scores = new FlakinessScorer().scoreAll(db);
    }
    if (top > 0 && scores.size() > top) {
      scores = scores.subList(0, top);
    }
    PrintWriter out = spec.commandLine().getOut();
    switch (format) {
      case JSON -> out.print(ScoreJson.render(scores));
      case MD -> out.print(markdown(scores));
    }
    out.flush();
    return 0;
  }

  private String markdown(List<FlakeScore> scores) {
    StringBuilder md = new StringBuilder();
    md.append("| # | Test | Score | Runs | Failures | Recovered | Flips | Messages |\n");
    md.append("|--:|------|------:|-----:|---------:|----------:|------:|---------:|\n");
    int rank = 1;
    for (FlakeScore s : scores) {
      md.append(
          String.format(
              Locale.ROOT,
              "| %d | `%s` | %.3f | %d | %d | %d | %d/%d | %d |%n",
              rank++,
              s.testId(),
              s.score(),
              s.runs(),
              s.failures(),
              s.recoveredFailures(),
              s.flips(),
              s.flipPairs(),
              s.distinctMessages()));
    }
    if (scores.isEmpty()) {
      md.append("\nNo runs in the history yet; run `flake ingest` first.\n");
    }
    int explained = 0;
    for (FlakeScore s : scores) {
      if (explained++ >= explain || s.score() == 0) {
        break;
      }
      md.append('\n').append(s.explain());
    }
    return md.toString();
  }
}
