package io.github.byreshb.flake.junit;

import io.github.byreshb.flake.model.Outcome;
import java.util.List;
import java.util.Locale;

/**
 * Renders {@link ObservedOutcome}s as a JUnit XML report in the shape {@code
 * io.github.byreshb.flake.ingest.JUnitXmlParser} reads, so an observed report is ingestible with
 * {@code flake ingest} exactly like a real Surefire report.
 */
final class ObservedReportWriter {

  private ObservedReportWriter() {}

  /**
   * Renders one test class's observed outcomes as a {@code testsuite} document.
   *
   * @param className the test class
   * @param outcomes the outcomes, in the order they were observed
   * @return the XML document
   */
  static String render(String className, List<ObservedOutcome> outcomes) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<testsuite name=\"").append(escape(className)).append("\">\n");
    for (ObservedOutcome outcome : outcomes) {
      xml.append("  <testcase name=\"")
          .append(escape(outcome.methodName()))
          .append("\" classname=\"")
          .append(escape(className))
          .append("\" time=\"")
          .append(String.format(Locale.ROOT, "%.3f", outcome.duration().toNanos() / 1e9))
          .append('"');
      if (outcome.outcome() == Outcome.PASS) {
        xml.append("/>\n");
        continue;
      }
      String tag = outcome.outcome() == Outcome.ERROR ? "error" : "failure";
      xml.append(">\n    <").append(tag);
      if (outcome.failureType() != null) {
        xml.append(" type=\"").append(escape(outcome.failureType())).append('"');
      }
      if (outcome.failureMessage() != null) {
        xml.append(" message=\"").append(escape(outcome.failureMessage())).append('"');
      }
      xml.append("/>\n  </testcase>\n");
    }
    xml.append("</testsuite>\n");
    return xml.toString();
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
        case '\'' -> escaped.append("&apos;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }
}
