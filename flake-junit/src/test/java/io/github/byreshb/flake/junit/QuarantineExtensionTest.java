package io.github.byreshb.flake.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.byreshb.flake.ingest.JUnitXmlParser;
import io.github.byreshb.flake.ingest.TestCaseResult;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.quarantine.QuarantineEntry;
import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

class QuarantineExtensionTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.now(CLOCK);

  private static QuarantineEntry entry(Class<?> testClass, String method, LocalDate expires) {
    return new QuarantineEntry(
        new io.github.byreshb.flake.model.TestId(testClass.getName(), method),
        "reason",
        "owner",
        TODAY.minusDays(10),
        expires,
        null);
  }

  // --- Scenario: an expired entry fails the whole class before any test runs ---

  static boolean expiredScenarioTestRan;

  static class ExpiredEntryFailsTheClass {

    @RegisterExtension
    static QuarantineExtension ext =
        new QuarantineExtension(
            QuarantineLedger.of(
                List.of(entry(ExpiredEntryFailsTheClass.class, "anyTest", TODAY.minusDays(1)))),
            QuarantineMode.OBSERVE,
            Path.of("target/unused"),
            CLOCK);

    @Test
    void anyTest() {
      expiredScenarioTestRan = true;
    }
  }

  @Test
  void expiredEntryFailsTheClassBeforeAnyTestRuns() {
    expiredScenarioTestRan = false;

    Events containers =
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ExpiredEntryFailsTheClass.class))
            .execute()
            .containerEvents();

    assertThat(containers.failed().count()).isEqualTo(1);
    String message =
        containers.failed().stream()
            .findFirst()
            .flatMap(e -> e.getPayload(org.junit.platform.engine.TestExecutionResult.class))
            .flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable)
            .map(Throwable::getMessage)
            .orElse("");
    assertThat(message).contains("expired on").contains("anyTest");
    assertThat(expiredScenarioTestRan).isFalse();
  }

  // --- Scenario: skip mode disables a quarantined test but leaves others alone ---

  static class SkipModeDisablesTheQuarantinedTest {

    @RegisterExtension
    static QuarantineExtension ext =
        new QuarantineExtension(
            QuarantineLedger.of(
                List.of(
                    entry(SkipModeDisablesTheQuarantinedTest.class, "flaky", TODAY.plusDays(30)))),
            QuarantineMode.SKIP,
            Path.of("target/unused"),
            CLOCK);

    @Test
    void flaky() {
      Assertions.fail("should have been skipped");
    }

    @Test
    void stable() {
      // passes
    }
  }

  @Test
  void skipModeDisablesTheQuarantinedTestAndRunsOthers() {
    Events tests =
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(SkipModeDisablesTheQuarantinedTest.class))
            .execute()
            .testEvents();

    assertThat(tests.skipped().count()).isEqualTo(1);
    assertThat(tests.succeeded().count()).isEqualTo(1);
    assertThat(tests.failed().count()).isZero();
  }

  // --- Scenario: observe mode catches a quarantined failure, records both outcomes, and does not
  //     affect an unrelated, unquarantined failure. ---

  static class ObserveModeRecordsRealOutcomes {

    @RegisterExtension
    static QuarantineExtension ext =
        new QuarantineExtension(
            QuarantineLedger.of(
                List.of(
                    entry(ObserveModeRecordsRealOutcomes.class, "flaky", TODAY.plusDays(30)),
                    entry(ObserveModeRecordsRealOutcomes.class, "quietPass", TODAY.plusDays(30)))),
            QuarantineMode.OBSERVE,
            OBSERVE_DIR,
            CLOCK);

    @Test
    void flaky() {
      throw new AssertionError("boom");
    }

    @Test
    void quietPass() {
      // quarantined, but passes: should be observed as PASS
    }

    @Test
    void brokenControl() {
      Assertions.fail("not quarantined, must fail the build");
    }
  }

  static Path OBSERVE_DIR;

  @Test
  void observeModeCatchesAQuarantinedFailureAndLeavesUnquarantinedFailuresAlone(@TempDir Path tmp) {
    OBSERVE_DIR = tmp;

    Events tests =
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ObserveModeRecordsRealOutcomes.class))
            .execute()
            .testEvents();

    // flaky's real failure is caught, so JUnit/Surefire see it as a pass; only the genuinely
    // unquarantined failure is reported.
    assertThat(tests.succeeded().count()).isEqualTo(2);
    assertThat(tests.failed().count()).isEqualTo(1);

    Path report = tmp.resolve("TEST-" + ObserveModeRecordsRealOutcomes.class.getName() + ".xml");
    assertThat(report).exists();

    Map<String, TestCaseResult> byMethod =
        new JUnitXmlParser()
            .parse(report).stream()
                .collect(Collectors.toMap(r -> r.testId().methodName(), Function.identity()));

    assertThat(byMethod).containsOnlyKeys("flaky", "quietPass");
    assertThat(byMethod.get("flaky").last().outcome()).isEqualTo(Outcome.FAIL);
    assertThat(byMethod.get("flaky").last().failureMessage()).isEqualTo("boom");
    assertThat(byMethod.get("quietPass").last().outcome()).isEqualTo(Outcome.PASS);
  }

  // --- Scenario: only plain @Test methods are intercepted; a parameterized test still fails. ---

  static class ParameterizedTestsAreNotIntercepted {

    @RegisterExtension
    static QuarantineExtension ext =
        new QuarantineExtension(
            QuarantineLedger.of(
                List.of(
                    entry(
                        ParameterizedTestsAreNotIntercepted.class,
                        "paramTest",
                        TODAY.plusDays(30)))),
            QuarantineMode.OBSERVE,
            Path.of("target/unused"),
            CLOCK);

    @ParameterizedTest
    @ValueSource(ints = 1)
    void paramTest(int value) {
      Assertions.fail("parameterized tests are not observed, so this must fail the build");
    }
  }

  @Test
  void parameterizedTestsAreNotCaughtByObserveMode() {
    Events tests =
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ParameterizedTestsAreNotIntercepted.class))
            .execute()
            .testEvents();

    assertThat(tests.failed().count()).isEqualTo(1);
  }

  // --- Scenario: a class with no quarantined tests and nothing expired behaves exactly as if the
  //     extension were not present, and writes no observed report. ---

  static class NothingQuarantined {

    @RegisterExtension
    static QuarantineExtension ext =
        new QuarantineExtension(
            QuarantineLedger.empty(), QuarantineMode.OBSERVE, NOTHING_DIR, CLOCK);

    @Test
    void ordinary() {
      // passes
    }

    @RepeatedTest(2)
    void repeated() {
      // passes; repeated tests are test-template invocations, exercised here to confirm the
      // extension does not interfere with them either.
    }
  }

  static Path NOTHING_DIR;

  @Test
  void aClassWithNothingQuarantinedIsUnaffectedAndWritesNoReport(@TempDir Path tmp) {
    NOTHING_DIR = tmp;

    Events tests =
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NothingQuarantined.class))
            .execute()
            .testEvents();

    assertThat(tests.succeeded().count()).isEqualTo(3);
    assertThat(tests.failed().count()).isZero();
    assertThat(tmp.resolve("TEST-" + NothingQuarantined.class.getName() + ".xml")).doesNotExist();
  }

  // --- Scenario: an ERROR outcome, and message characters that need XML escaping, round-trip. ---

  static class ObserveModeRecordsAnErrorWithSpecialCharacters {

    @RegisterExtension
    static QuarantineExtension ext =
        new QuarantineExtension(
            QuarantineLedger.of(
                List.of(
                    entry(
                        ObserveModeRecordsAnErrorWithSpecialCharacters.class,
                        "broken",
                        TODAY.plusDays(30)))),
            QuarantineMode.OBSERVE,
            ERROR_DIR,
            CLOCK);

    @Test
    void broken() {
      throw new IllegalStateException("<a> & \"b\" 'c'");
    }
  }

  static Path ERROR_DIR;

  @Test
  void observeModeRecordsAnErrorOutcomeAndEscapesTheMessage(@TempDir Path tmp) {
    ERROR_DIR = tmp;

    EngineTestKit.engine("junit-jupiter")
        .selectors(selectClass(ObserveModeRecordsAnErrorWithSpecialCharacters.class))
        .execute();

    Path report =
        tmp.resolve(
            "TEST-" + ObserveModeRecordsAnErrorWithSpecialCharacters.class.getName() + ".xml");
    TestCaseResult result = new JUnitXmlParser().parse(report).get(0);

    assertThat(result.last().outcome()).isEqualTo(Outcome.ERROR);
    assertThat(result.last().failureType()).isEqualTo("java.lang.IllegalStateException");
    assertThat(result.last().failureMessage()).isEqualTo("<a> & \"b\" 'c'");
  }

  // --- Scenario: afterAll's own I/O failure is reported clearly rather than swallowed. ---

  static class ObserveModeWithAnUnwritableReportDirectory {

    @RegisterExtension
    static QuarantineExtension ext =
        new QuarantineExtension(
            QuarantineLedger.of(
                List.of(
                    entry(
                        ObserveModeWithAnUnwritableReportDirectory.class,
                        "flaky",
                        TODAY.plusDays(30)))),
            QuarantineMode.OBSERVE,
            BLOCKED_DIR,
            CLOCK);

    @Test
    void flaky() {
      throw new AssertionError("boom");
    }
  }

  static Path BLOCKED_DIR;

  @Test
  void aFailureWritingTheObservedReportFailsTheClassWithAClearMessage(@TempDir Path tmp)
      throws IOException {
    Path blocked = tmp.resolve("blocked");
    Files.writeString(blocked, "not a directory");
    BLOCKED_DIR = blocked;

    Events containers =
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ObserveModeWithAnUnwritableReportDirectory.class))
            .execute()
            .containerEvents();

    assertThat(containers.failed().count()).isEqualTo(1);
    String message =
        containers.failed().stream()
            .findFirst()
            .flatMap(e -> e.getPayload(org.junit.platform.engine.TestExecutionResult.class))
            .flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable)
            .map(Throwable::getMessage)
            .orElse("");
    assertThat(message).contains("cannot write observed report");
  }

  // --- Scenario: the real, no-argument entry point used by @ExtendWith(QuarantineExtension.class),
  //     configured entirely through system properties, exactly as a consumer would use it. ---

  @ExtendWith(QuarantineExtension.class)
  static class UsedViaExtendWith {

    @Test
    void quarantinedBySystemProperties() {
      Assertions.fail("should have been skipped by -Dflake.quarantine=skip");
    }
  }

  @AfterEach
  void clearSystemProperties() {
    System.clearProperty(QuarantineExtension.MODE_PROPERTY);
    System.clearProperty(QuarantineExtension.LEDGER_PROPERTY);
    System.clearProperty(QuarantineExtension.OBSERVED_DIR_PROPERTY);
  }

  @Test
  void theRealNoArgConstructorReadsConfigurationFromSystemProperties(@TempDir Path tmp)
      throws IOException {
    // The real constructor reads the real system clock, so the ledger dates here must be relative
    // to the real "now", not the fixed CLOCK used by the other scenarios in this file.
    LocalDate realToday = LocalDate.now();
    Path ledger = tmp.resolve("quarantine.yaml");
    Files.writeString(
        ledger,
        "entries:\n"
            + "  - test: "
            + UsedViaExtendWith.class.getName()
            + "#quarantinedBySystemProperties\n"
            + "    reason: r\n"
            + "    owner: o\n"
            + "    added: "
            + realToday.minusDays(10)
            + "\n"
            + "    expires: "
            + realToday.plusDays(30)
            + "\n");
    System.setProperty(QuarantineExtension.MODE_PROPERTY, "skip");
    System.setProperty(QuarantineExtension.LEDGER_PROPERTY, ledger.toString());

    Events tests =
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(UsedViaExtendWith.class))
            .execute()
            .testEvents();

    assertThat(tests.skipped().count()).isEqualTo(1);
    assertThat(tests.failed().count()).isZero();
  }
}
