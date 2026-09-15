package io.github.byreshb.flake.junit;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.quarantine.QuarantineEntry;
import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Enforces the quarantine ledger during a test run. Register it with
 * {@code @ExtendWith(QuarantineExtension.class)} on a test class (or a base class, or globally via
 * {@code META-INF/services}):
 *
 * <ul>
 *   <li>a test whose ledger entry has expired fails the whole class before any test runs, with a
 *       message naming every expired entry;
 *   <li>with {@code -Dflake.quarantine=skip}, a quarantined, unexpired test is not run and is
 *       reported disabled;
 *   <li>otherwise (the default, {@code observe}), a quarantined, unexpired test runs as normal, but
 *       a failure is caught rather than allowed to fail the build; its real outcome is written to a
 *       separate JUnit XML report under {@code .flake/observed/} (override with {@code
 *       -Dflake.observed.dir}), ready for {@code flake ingest} exactly like a Surefire report.
 * </ul>
 *
 * The ledger location defaults to {@link QuarantineLedger#DEFAULT_PATH} and can be overridden with
 * {@code -Dflake.ledger}. Only plain {@code @Test} methods are intercepted in observe mode;
 * parameterized and dynamic tests still run, but a failure of a quarantined one is not caught.
 * {@code @Nested} test classes are not specifically supported: each is treated as its own class.
 */
public final class QuarantineExtension
    implements BeforeAllCallback, AfterAllCallback, ExecutionCondition, InvocationInterceptor {

  /** System property selecting {@link QuarantineMode#SKIP} ({@code skip}) or the default. */
  public static final String MODE_PROPERTY = "flake.quarantine";

  /** System property overriding the ledger path (default {@link QuarantineLedger#DEFAULT_PATH}). */
  public static final String LEDGER_PROPERTY = "flake.ledger";

  /** System property overriding the observed-report directory (default {@code .flake/observed}). */
  public static final String OBSERVED_DIR_PROPERTY = "flake.observed.dir";

  private static final String DEFAULT_OBSERVED_DIR = ".flake/observed";
  private static final Namespace NAMESPACE = Namespace.create(QuarantineExtension.class);
  private static final String OBSERVATIONS_KEY = "observations";

  private final QuarantineLedger ledger;
  private final QuarantineMode mode;
  private final Path observedDir;
  private final Clock clock;

  /**
   * Creates the extension from system properties, for
   * {@code @ExtendWith(QuarantineExtension.class)}.
   */
  public QuarantineExtension() {
    this(
        QuarantineLedger.load(ledgerPathFromProperty()),
        modeFromProperty(),
        observedDirFromProperty(),
        Clock.systemDefaultZone());
  }

  /**
   * Creates the extension with explicit configuration, for {@code @RegisterExtension} and tests.
   *
   * @param ledger the quarantine ledger
   * @param mode skip or observe
   * @param observedDir where observe-mode reports are written
   * @param clock source of "now" for expiry checks and observed durations
   */
  public QuarantineExtension(
      QuarantineLedger ledger, QuarantineMode mode, Path observedDir, Clock clock) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.observedDir = Objects.requireNonNull(observedDir, "observedDir");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  static Path ledgerPathFromProperty() {
    return Path.of(System.getProperty(LEDGER_PROPERTY, QuarantineLedger.DEFAULT_PATH.toString()));
  }

  static QuarantineMode modeFromProperty() {
    return "skip".equalsIgnoreCase(System.getProperty(MODE_PROPERTY, "observe"))
        ? QuarantineMode.SKIP
        : QuarantineMode.OBSERVE;
  }

  static Path observedDirFromProperty() {
    return Path.of(System.getProperty(OBSERVED_DIR_PROPERTY, DEFAULT_OBSERVED_DIR));
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    String className = context.getRequiredTestClass().getName();
    LocalDate today = LocalDate.now(clock);
    List<QuarantineEntry> expired =
        ledger.entries().stream()
            .filter(entry -> entry.testId().className().equals(className))
            .filter(entry -> entry.isExpired(today))
            .toList();
    if (!expired.isEmpty()) {
      throw new QuarantineExpiredException(className, expired);
    }
  }

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (context.getTestMethod().isEmpty() || mode != QuarantineMode.SKIP) {
      return ConditionEvaluationResult.enabled("not skipping");
    }
    return quarantineOf(context)
        .filter(entry -> !entry.isExpired(LocalDate.now(clock)))
        .map(
            entry ->
                ConditionEvaluationResult.disabled(
                    "quarantined by "
                        + entry.owner()
                        + " until "
                        + entry.expires()
                        + ": "
                        + entry.reason()))
        .orElseGet(() -> ConditionEvaluationResult.enabled("not quarantined"));
  }

  @Override
  public void interceptTestMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext context)
      throws Throwable {
    if (mode != QuarantineMode.OBSERVE) {
      invocation.proceed();
      return;
    }
    Optional<QuarantineEntry> entry = quarantineOf(context);
    if (entry.isEmpty() || entry.get().isExpired(LocalDate.now(clock))) {
      invocation.proceed();
      return;
    }
    Instant start = clock.instant();
    try {
      invocation.proceed();
      observe(context, start, Outcome.PASS, null, null);
    } catch (Throwable failure) {
      Outcome outcome = failure instanceof AssertionError ? Outcome.FAIL : Outcome.ERROR;
      observe(context, start, outcome, failure.getClass().getName(), failure.getMessage());
      // Deliberately not rethrown: an observed failure must not fail the build.
    }
  }

  private Optional<QuarantineEntry> quarantineOf(ExtensionContext context) {
    TestId id =
        new TestId(
            context.getRequiredTestClass().getName(), context.getRequiredTestMethod().getName());
    return ledger.find(id);
  }

  @SuppressWarnings("unchecked")
  private void observe(
      ExtensionContext context,
      Instant start,
      Outcome outcome,
      String failureType,
      String failureMessage) {
    Duration duration = Duration.between(start, clock.instant());
    ObservedOutcome observed =
        new ObservedOutcome(
            context.getRequiredTestMethod().getName(),
            duration,
            outcome,
            failureType,
            failureMessage);
    Store classStore = context.getParent().orElse(context).getStore(NAMESPACE);
    classStore
        .getOrComputeIfAbsent(
            OBSERVATIONS_KEY, key -> new CopyOnWriteArrayList<ObservedOutcome>(), List.class)
        .add(observed);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void afterAll(ExtensionContext context) {
    List<ObservedOutcome> observed = context.getStore(NAMESPACE).get(OBSERVATIONS_KEY, List.class);
    if (observed == null || observed.isEmpty()) {
      return;
    }
    String className = context.getRequiredTestClass().getName();
    try {
      Files.createDirectories(observedDir);
      Path file = observedDir.resolve("TEST-" + className + ".xml");
      Files.writeString(
          file, ObservedReportWriter.render(className, observed), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write observed report for " + className, e);
    }
  }
}
