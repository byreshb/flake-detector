package io.github.byreshb.flake.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JUnitXmlParserTest {

  private static final Path FIXTURES = Path.of("src/test/resources/surefire");
  private final JUnitXmlParser parser = new JUnitXmlParser();

  private Map<String, TestCaseResult> checkout() {
    return parser.parse(FIXTURES.resolve("TEST-com.acme.CheckoutTest.xml")).stream()
        .collect(Collectors.toMap(r -> r.testId().methodName(), Function.identity()));
  }

  @Test
  void readsEveryTestCaseOfASurefireReport() {
    List<TestCaseResult> results = parser.parse(FIXTURES.resolve("TEST-com.acme.CheckoutTest.xml"));

    assertThat(results)
        .extracting(r -> r.testId().toString())
        .containsExactly(
            "com.acme.CheckoutTest#addsItem",
            "com.acme.CheckoutTest#appliesCoupon",
            "com.acme.CheckoutTest#rejectsExpiredCard",
            "com.acme.CheckoutTest#loadsInventory",
            "com.acme.CheckoutTest#printsReceipt");
  }

  @Test
  void passedTestHasOneExecution() {
    TestCaseResult result = checkout().get("addsItem");

    assertThat(result.executions()).containsExactly(Execution.PASSED);
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(21));
  }

  @Test
  void flakyElementsMeanFailuresFollowedByAPass() {
    TestCaseResult result = checkout().get("appliesCoupon");

    assertThat(result.executions())
        .extracting(Execution::outcome)
        .containsExactly(Outcome.FAIL, Outcome.ERROR, Outcome.PASS);
    assertThat(result.executions().get(0).failureMessage())
        .isEqualTo("expected: <12.50> but was: <12.40>");
    assertThat(result.executions().get(0).failureType())
        .isEqualTo("org.opentest4j.AssertionFailedError");
    assertThat(result.executions().get(1).failureType()).isEqualTo("java.net.ConnectException");
    assertThat(result.last()).isEqualTo(Execution.PASSED);
  }

  @Test
  void rerunFailuresMeanTheTestFailedEveryTime() {
    TestCaseResult result = checkout().get("rejectsExpiredCard");

    assertThat(result.executions())
        .extracting(Execution::outcome)
        .containsExactly(Outcome.FAIL, Outcome.FAIL, Outcome.FAIL);
    assertThat(result.executions())
        .extracting(Execution::failureMessageHash)
        .containsOnly(result.executions().get(0).failureMessageHash());
  }

  @Test
  void errorWithoutMessageAttributeUsesFirstLineOfTheBody() {
    TestCaseResult result = checkout().get("loadsInventory");

    assertThat(result.executions())
        .extracting(Execution::outcome)
        .containsExactly(Outcome.ERROR, Outcome.ERROR);
    assertThat(result.executions().get(0).failureMessage())
        .isEqualTo("java.lang.IllegalStateException: pool exhausted after 30000 ms");
    assertThat(result.executions().get(1).failureMessage())
        .isEqualTo("pool exhausted after 30012 ms");
  }

  @Test
  void skippedTestHasOneSkippedExecution() {
    TestCaseResult result = checkout().get("printsReceipt");

    assertThat(result.executions()).containsExactly(Execution.SKIPPED);
    assertThat(result.duration()).isZero();
    assertThat(result.last().failureMessageHash()).isNull();
  }

  @Test
  void keepsParameterisedNamesAndIgnoresOutputElements() {
    List<TestCaseResult> results = parser.parse(FIXTURES.resolve("TEST-com.acme.SearchTest.xml"));

    assertThat(results)
        .extracting(r -> r.testId().methodName())
        .containsExactly("findsByName(String)[1]", "findsByName(String)[2]", "findsNothing");
    assertThat(results)
        .allSatisfy(r -> assertThat(r.executions()).containsExactly(Execution.PASSED));
  }

  @Test
  void walksNestedTestsuitesAndFallsBackToSuiteNameAsClass() {
    List<TestCaseResult> results = parser.parse(FIXTURES.resolve("testsuites-nested.xml"));

    assertThat(results)
        .extracting(r -> r.testId().toString())
        .containsExactly("com.acme.OuterTest#outer", "com.acme.InnerTest#inner");
    assertThat(results.get(0).duration()).isEqualTo(Duration.ofMillis(500));
    Execution inner = results.get(1).last();
    assertThat(inner.outcome()).isEqualTo(Outcome.FAIL);
    assertThat(inner.failureType()).isNull();
    assertThat(inner.failureMessage()).isEqualTo("java.lang.AssertionError: boom");
  }

  @Test
  void rejectsFilesThatAreNotReports() {
    assertThatThrownBy(() -> parser.parse(FIXTURES.resolve("not-a-report.xml")))
        .isInstanceOf(ReportFormatException.class)
        .hasMessageContaining("not-a-report.xml")
        .hasMessageContaining("testsuite");
    assertThatThrownBy(() -> parser.parse(FIXTURES.resolve("broken.xml")))
        .isInstanceOf(ReportFormatException.class)
        .hasMessageContaining("well-formed");
    assertThatThrownBy(() -> parser.parse(FIXTURES.resolve("missing.xml")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void rejectsTestCasesWithoutNamesAndInvalidTimes() {
    assertThatThrownBy(() -> parse("<testsuite name=\"s\"><testcase classname=\"c\"/></testsuite>"))
        .isInstanceOf(ReportFormatException.class)
        .hasMessageContaining("classname or name");
    assertThatThrownBy(
            () ->
                parse(
                    "<testsuite name=\"s\"><testcase classname=\"c\" name=\"n\""
                        + " time=\"x\"/></testsuite>"))
        .isInstanceOf(ReportFormatException.class)
        .hasMessageContaining("time");
  }

  @Test
  void refusesDoctypeDeclarations() {
    String xml =
        "<!DOCTYPE testsuite [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<testsuite name=\"s\"><testcase classname=\"c\" name=\"&x;\"/></testsuite>";

    assertThatThrownBy(() -> parse(xml)).isInstanceOf(ReportFormatException.class);
  }

  @Test
  void convertsExecutionsToRunsTiedToABuild() {
    BuildRun build = new BuildRun("77", "abc123", 2, Instant.parse("2026-09-01T10:00:00Z"));
    TestCaseResult result = checkout().get("appliesCoupon");

    List<TestRun> runs = result.toRuns(build, "main", "ubuntu-latest");

    assertThat(runs).hasSize(3);
    assertThat(runs).extracting(TestRun::rerun).containsExactly(0, 1, 2);
    assertThat(runs)
        .extracting(TestRun::outcome)
        .containsExactly(Outcome.FAIL, Outcome.ERROR, Outcome.PASS);
    assertThat(runs).extracting(TestRun::commit).containsOnly("abc123");
    assertThat(runs).extracting(TestRun::branch).containsOnly("main");
    assertThat(runs).extracting(TestRun::runner).containsOnly("ubuntu-latest");
    assertThat(runs.get(0).failureMessageHash()).hasSize(16);
    assertThat(runs.get(2).failureMessageHash()).isNull();
    assertThat(runs.get(0).testId()).isEqualTo(TestId.parse("com.acme.CheckoutTest#appliesCoupon"));
  }

  @Test
  void testCaseResultNeedsAtLeastOneExecution() {
    assertThatThrownBy(() -> new TestCaseResult(TestId.parse("a.B#c"), Duration.ZERO, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private List<TestCaseResult> parse(String xml) {
    return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
