package io.github.byreshb.flake.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.score.FlakinessScorer;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlReportTest {

  private static final TestId FLAKY = TestId.parse("com.acme.CheckoutTest#appliesCoupon");

  private static TestRun run(int day, int rerun, Outcome outcome) {
    BuildRun build =
        new BuildRun(
            "b" + day,
            "c" + day,
            1,
            Instant.parse("2026-01-01T00:00:00Z").plus(Duration.ofDays(day)));
    return new TestRun(
        FLAKY,
        build,
        "main",
        "linux",
        rerun,
        Duration.ofMillis(1),
        outcome,
        outcome.isFailure() ? "h" : null);
  }

  @Test
  void rendersASelfContainedDocumentWithATableAndSparkline() {
    try (SqliteRunStore store = SqliteRunStore.inMemory()) {
      store.record(List.of(run(0, 0, Outcome.FAIL), run(0, 1, Outcome.PASS)));
      List<ReportEntry> entries = Reports.build(store, new FlakinessScorer(), 10);

      String html = HtmlReport.render("Flake report", entries, 5);

      assertThat(html).startsWith("<!doctype html>\n<html lang=\"en\">");
      assertThat(html).contains("<title>Flake report</title>");
      assertThat(html).contains("<code>com.acme.CheckoutTest#appliesCoupon</code>");
      assertThat(html).contains("<svg class=\"spark\"");
      assertThat(html).contains("fill=\"#c8102e\"");
      assertThat(html).contains("fill=\"#2e8540\"");
      assertThat(html).contains("<h2>Top suspects</h2>");
      assertThat(html).contains("<pre>com.acme.CheckoutTest#appliesCoupon: score 0.");
      assertThat(html).doesNotContain("<script");
      assertThat(html).doesNotContain("http://").doesNotContain("https://");
    }
  }

  @Test
  void emptyHistoryIsReported() {
    String html = HtmlReport.render("Flake report", List.of(), 5);

    assertThat(html).contains("<p>No runs in the history yet.</p>");
  }

  @Test
  void noSuspectsAboveZeroIsReported() {
    ReportEntry stable =
        new ReportEntry(
            new FlakinessScorer().score(FLAKY, List.of(run(0, 0, Outcome.PASS))), List.of());

    String html = HtmlReport.render("Flake report", List.of(stable), 5);

    assertThat(html).contains("<p>No test scores above 0.</p>");
  }

  @Test
  void escapesHtmlSpecialCharactersInTheTestId() {
    TestId weird = TestId.parse("a.B#c<d>&\"e\"");
    ReportEntry entry = new ReportEntry(new FlakinessScorer().score(weird, List.of()), List.of());

    String html = HtmlReport.render("t", List.of(entry), 0);

    assertThat(html).contains("a.B#c&lt;d&gt;&amp;&quot;e&quot;");
    assertThat(html).doesNotContain("<d>");
  }

  @Test
  void sparklineHeightsAndDashForEmptyTrend() {
    assertThat(HtmlReport.sparkline(List.of())).isEqualTo("&mdash;");
    String svg = HtmlReport.sparkline(List.of(Outcome.PASS, Outcome.ERROR));
    assertThat(svg).startsWith("<svg class=\"spark\"").endsWith("</svg>");
    assertThat(svg).contains("fill=\"#7a1230\"");
  }
}
