package io.github.byreshb.flake.report;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.score.FlakeScore;
import java.util.List;
import java.util.Locale;

/**
 * Renders a single self-contained HTML file: a ranked table with an inline SVG trend sparkline per
 * test, and explanations of the top suspects. There are no external resources (no linked CSS, JS,
 * images or fonts), so the file can be opened directly or attached to a build as an artifact.
 */
public final class HtmlReport {

  private static final int BAR_WIDTH = 3;
  private static final int BAR_GAP = 1;
  private static final int SPARK_HEIGHT = 18;

  private HtmlReport() {}

  /**
   * Renders the report.
   *
   * @param title document title, shown in the browser tab and as a heading
   * @param entries the scored tests, in ranking order
   * @param explain how many of the top tests (with a score above 0) to explain
   * @return the complete HTML document
   */
  public static String render(String title, List<ReportEntry> entries, int explain) {
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
    html.append("<title>").append(escape(title)).append("</title>\n");
    html.append(STYLE);
    html.append("</head>\n<body>\n");
    html.append("<h1>").append(escape(title)).append("</h1>\n");
    if (entries.isEmpty()) {
      html.append("<p>No runs in the history yet.</p>\n");
    } else {
      appendTable(html, entries);
      appendSuspects(html, entries, explain);
    }
    html.append("</body>\n</html>\n");
    return html.toString();
  }

  private static void appendTable(StringBuilder html, List<ReportEntry> entries) {
    html.append("<table>\n<thead><tr>")
        .append("<th>#</th><th>Test</th><th>Score</th><th>Runs</th><th>Failures</th>")
        .append("<th>Recovered</th><th>Flips</th><th>Messages</th><th>Trend</th>")
        .append("</tr></thead>\n<tbody>\n");
    int rank = 1;
    for (ReportEntry entry : entries) {
      FlakeScore s = entry.score();
      html.append("<tr><td>")
          .append(rank++)
          .append("</td><td><code>")
          .append(escape(s.testId().toString()))
          .append("</code></td><td>")
          .append(String.format(Locale.ROOT, "%.3f", s.score()))
          .append("</td><td>")
          .append(s.runs())
          .append("</td><td>")
          .append(s.failures())
          .append("</td><td>")
          .append(s.recoveredFailures())
          .append("</td><td>")
          .append(s.flips())
          .append('/')
          .append(s.flipPairs())
          .append("</td><td>")
          .append(s.distinctMessages())
          .append("</td><td>")
          .append(sparkline(entry.trend()))
          .append("</td></tr>\n");
    }
    html.append("</tbody>\n</table>\n");
  }

  private static void appendSuspects(StringBuilder html, List<ReportEntry> entries, int explain) {
    html.append("<h2>Top suspects</h2>\n");
    int explained = 0;
    boolean any = false;
    for (ReportEntry entry : entries) {
      if (explained++ >= explain || entry.score().score() == 0) {
        break;
      }
      any = true;
      html.append("<pre>").append(escape(entry.score().explain())).append("</pre>\n");
    }
    if (!any) {
      html.append("<p>No test scores above 0.</p>\n");
    }
  }

  /**
   * A trend as an inline SVG bar chart: a short bar for a pass, a tall red bar for a failure, a
   * tall maroon bar for an error, oldest run on the left.
   *
   * @param trend outcomes in chronological order
   * @return an {@code <svg>} element, or an em dash when there is no history
   */
  static String sparkline(List<Outcome> trend) {
    if (trend.isEmpty()) {
      return "&mdash;";
    }
    int width = trend.size() * (BAR_WIDTH + BAR_GAP) - BAR_GAP;
    StringBuilder svg =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "<svg class=\"spark\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">",
                width,
                SPARK_HEIGHT,
                width,
                SPARK_HEIGHT));
    int x = 0;
    for (Outcome outcome : trend) {
      int height =
          switch (outcome) {
            case PASS -> 5;
            case FAIL, ERROR -> SPARK_HEIGHT;
            case SKIPPED -> 0;
          };
      String color =
          switch (outcome) {
            case PASS -> "#2e8540";
            case FAIL -> "#c8102e";
            case ERROR -> "#7a1230";
            case SKIPPED -> "none";
          };
      if (height > 0) {
        svg.append(
            String.format(
                Locale.ROOT,
                "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"%s\"/>",
                x,
                SPARK_HEIGHT - height,
                BAR_WIDTH,
                height,
                color));
      }
      x += BAR_WIDTH + BAR_GAP;
    }
    return svg.append("</svg>").toString();
  }

  private static String escape(String text) {
    StringBuilder escaped = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static final String STYLE =
      "<style>\n"
          + "body { font-family: -apple-system, Segoe UI, Helvetica, Arial, sans-serif; margin:"
          + " 2rem; color: #1a1a1a; }\n"
          + "table { border-collapse: collapse; width: 100%; }\n"
          + "th, td { padding: 0.35rem 0.6rem; text-align: right; border-bottom: 1px solid #ddd;"
          + " }\n"
          + "th:nth-child(2), td:nth-child(2) { text-align: left; }\n"
          + "code { font-family: ui-monospace, Menlo, Consolas, monospace; }\n"
          + "pre { background: #f6f6f6; padding: 0.75rem 1rem; border-radius: 6px; overflow-x:"
          + " auto; }\n"
          + "svg.spark { vertical-align: middle; }\n"
          + "</style>\n";
}
