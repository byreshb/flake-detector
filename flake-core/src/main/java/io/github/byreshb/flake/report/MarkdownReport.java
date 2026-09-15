package io.github.byreshb.flake.report;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.score.FlakeScore;
import java.util.List;
import java.util.Locale;

/** Renders a ranked Markdown table with a trend column and explanations of the top suspects. */
public final class MarkdownReport {

  private MarkdownReport() {}

  /**
   * Renders the report.
   *
   * @param entries the scored tests, in the order they should be ranked (see {@link
   *     io.github.byreshb.flake.score.FlakinessScorer#RANKING})
   * @param explain how many of the top tests (with a score above 0) to explain after the table
   * @return the Markdown document
   */
  public static String render(List<ReportEntry> entries, int explain) {
    StringBuilder md = new StringBuilder();
    md.append("| # | Test | Score | Runs | Failures | Recovered | Flips | Messages | Trend |\n");
    md.append("|--:|------|------:|-----:|---------:|----------:|------:|---------:|-------|\n");
    int rank = 1;
    for (ReportEntry entry : entries) {
      FlakeScore s = entry.score();
      md.append(
          String.format(
              Locale.ROOT,
              "| %d | `%s` | %.3f | %d | %d | %d | %d/%d | %d | `%s` |%n",
              rank++,
              s.testId(),
              s.score(),
              s.runs(),
              s.failures(),
              s.recoveredFailures(),
              s.flips(),
              s.flipPairs(),
              s.distinctMessages(),
              sparkline(entry.trend())));
    }
    int explained = 0;
    for (ReportEntry entry : entries) {
      if (explained++ >= explain || entry.score().score() == 0) {
        break;
      }
      md.append('\n').append(entry.score().explain());
    }
    return md.toString();
  }

  /**
   * A one-character-per-run trend, oldest first: {@code .} for a pass, {@code F} for a failure,
   * {@code E} for an error.
   *
   * @param trend outcomes in chronological order
   * @return the sparkline, empty when there is no history
   */
  static String sparkline(List<Outcome> trend) {
    StringBuilder line = new StringBuilder(trend.size());
    for (Outcome outcome : trend) {
      line.append(
          switch (outcome) {
            case PASS -> '.';
            case FAIL -> 'F';
            case ERROR -> 'E';
            case SKIPPED -> '?';
          });
    }
    return line.toString();
  }
}
