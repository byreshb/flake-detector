package io.github.byreshb.flake.ingest;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parses JUnit XML reports as written by Maven Surefire and Failsafe (and by most other tools that
 * emit the same format), including the Surefire 3 rerun elements.
 *
 * <p>Interpretation of a {@code testcase}:
 *
 * <ul>
 *   <li>no child, or only {@code system-out}/{@code system-err}: one passed execution;
 *   <li>{@code skipped}: one skipped execution;
 *   <li>{@code failure} or {@code error}: one failed execution, followed by one more failed
 *       execution per {@code rerunFailure}/{@code rerunError};
 *   <li>{@code flakyFailure}/{@code flakyError}: one failed execution per element, followed by a
 *       passed execution (the test passed on the final rerun).
 * </ul>
 */
public final class JUnitXmlParser {

  private static final Set<String> FAILURE_TAGS = Set.of("failure", "error");
  private static final Set<String> RERUN_TAGS = Set.of("rerunFailure", "rerunError");
  private static final Set<String> FLAKY_TAGS = Set.of("flakyFailure", "flakyError");

  private final DocumentBuilderFactory factory;

  /** Creates a parser with external entities disabled. */
  public JUnitXmlParser() {
    factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    try {
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("XML parser does not support secure processing", e);
    }
  }

  /**
   * Parses one report file.
   *
   * @param file path to a Surefire or Failsafe XML report
   * @return every test case in the report, in document order
   * @throws UncheckedIOException when the file cannot be read
   * @throws ReportFormatException when the content is not a JUnit XML report
   */
  public List<TestCaseResult> parse(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    } catch (ReportFormatException e) {
      throw new ReportFormatException(file + ": " + e.getMessage(), e);
    }
  }

  /**
   * Parses a report from a stream. The stream is not closed.
   *
   * @param in the XML content
   * @return every test case in the report, in document order
   * @throws ReportFormatException when the content is not a JUnit XML report
   */
  public List<TestCaseResult> parse(InputStream in) {
    Document document;
    try {
      DocumentBuilder builder = factory.newDocumentBuilder();
      // The default handler prints "[Fatal Error]" to stderr before throwing; stay quiet.
      builder.setErrorHandler(new DefaultHandler());
      document = builder.parse(in);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new ReportFormatException("not well-formed XML: " + e.getMessage(), e);
    }
    Element root = document.getDocumentElement();
    if (root == null
        || !(root.getTagName().equals("testsuite") || root.getTagName().equals("testsuites"))) {
      throw new ReportFormatException("root element must be testsuite or testsuites");
    }
    List<TestCaseResult> results = new ArrayList<>();
    collect(root, results);
    return results;
  }

  private void collect(Element element, List<TestCaseResult> results) {
    for (Element child : children(element)) {
      switch (child.getTagName()) {
        case "testsuite" -> collect(child, results);
        case "testcase" -> results.add(testCase(child, element));
        default -> {}
      }
    }
  }

  private TestCaseResult testCase(Element testcase, Element suite) {
    String className = testcase.getAttribute("classname");
    if (className.isBlank()) {
      className = suite.getAttribute("name");
    }
    String name = testcase.getAttribute("name");
    if (className.isBlank() || name.isBlank()) {
      throw new ReportFormatException("testcase without classname or name");
    }
    TestId id = new TestId(className, name);
    Duration duration = duration(testcase.getAttribute("time"));

    List<Execution> failures = new ArrayList<>();
    List<Execution> reruns = new ArrayList<>();
    List<Execution> flaky = new ArrayList<>();
    boolean skipped = false;
    for (Element child : children(testcase)) {
      String tag = child.getTagName();
      if (tag.equals("skipped")) {
        skipped = true;
      } else if (FAILURE_TAGS.contains(tag)) {
        failures.add(failed(tag, child));
      } else if (RERUN_TAGS.contains(tag)) {
        reruns.add(failed(tag, child));
      } else if (FLAKY_TAGS.contains(tag)) {
        flaky.add(failed(tag, child));
      }
    }

    List<Execution> executions = new ArrayList<>();
    if (!failures.isEmpty()) {
      executions.addAll(failures);
      executions.addAll(reruns);
    } else if (!flaky.isEmpty()) {
      executions.addAll(flaky);
      executions.add(Execution.PASSED);
    } else if (skipped) {
      executions.add(Execution.SKIPPED);
    } else {
      executions.add(Execution.PASSED);
    }
    return new TestCaseResult(id, duration, executions);
  }

  private static Execution failed(String tag, Element element) {
    Outcome outcome = tag.toLowerCase().contains("error") ? Outcome.ERROR : Outcome.FAIL;
    String type = element.hasAttribute("type") ? element.getAttribute("type") : null;
    String message = element.hasAttribute("message") ? element.getAttribute("message") : null;
    if (message == null) {
      String text = element.getTextContent();
      if (text != null && !text.isBlank()) {
        message = text.strip().lines().findFirst().orElse(null);
      }
    }
    return new Execution(outcome, type, message);
  }

  private static Duration duration(String time) {
    if (time == null || time.isBlank()) {
      return Duration.ZERO;
    }
    try {
      // Surefire writes seconds with three decimals. Other tools write "1,234.5" (thousands
      // separator) or, in some locales, "0,5" (decimal comma); handle both.
      String normalised = time.contains(".") ? time.replace(",", "") : time.replace(',', '.');
      BigDecimal seconds = new BigDecimal(normalised);
      return Duration.ofNanos(seconds.movePointRight(9).longValue());
    } catch (NumberFormatException e) {
      throw new ReportFormatException("invalid time attribute '" + time + "'", e);
    }
  }

  private static List<Element> children(Element element) {
    NodeList nodes = element.getChildNodes();
    List<Element> elements = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        elements.add((Element) node);
      }
    }
    return elements;
  }
}
