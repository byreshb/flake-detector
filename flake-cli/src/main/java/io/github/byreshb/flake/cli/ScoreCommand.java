package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.report.HtmlReport;
import io.github.byreshb.flake.report.MarkdownReport;
import io.github.byreshb.flake.report.ReportEntry;
import io.github.byreshb.flake.report.Reports;
import io.github.byreshb.flake.score.FlakinessScorer;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.io.PrintWriter;
import java.util.List;
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
    /** A Markdown table with a trend column, followed by explanations of the top suspects. */
    MD,
    /** A single self-contained HTML file with the same table, an inline SVG trend and suspects. */
    HTML,
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
          "How many of the top tests to explain in Markdown or HTML output (default:"
              + " ${DEFAULT-VALUE}).")
  private int explain;

  @Mixin private StoreOptions store;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    List<ReportEntry> entries;
    try (SqliteRunStore db = store.open()) {
      entries = Reports.build(db, new FlakinessScorer(), Reports.DEFAULT_TREND_POINTS);
    }
    if (top > 0 && entries.size() > top) {
      entries = entries.subList(0, top);
    }
    PrintWriter out = spec.commandLine().getOut();
    switch (format) {
      case JSON -> out.print(ScoreJson.render(entries.stream().map(ReportEntry::score).toList()));
      case HTML -> out.print(HtmlReport.render("Flake score", entries, explain));
      case MD -> out.print(markdown(entries));
    }
    out.flush();
    return 0;
  }

  private String markdown(List<ReportEntry> entries) {
    if (entries.isEmpty()) {
      return "No runs in the history yet; run `flake ingest` first.\n";
    }
    return MarkdownReport.render(entries, explain);
  }
}
