package io.github.byreshb.flake.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TestRunTest {

  private static final BuildRun BUILD =
      new BuildRun("run-1", "abc123", 1, Instant.parse("2026-09-01T10:00:00Z"));
  private static final TestId ID = TestId.parse("com.acme.CheckoutTest#appliesCoupon");

  @Test
  void exposesCommitAndTimestampOfTheBuild() {
    TestRun run =
        new TestRun(
            ID, BUILD, "main", "ubuntu-latest", 0, Duration.ofMillis(412), Outcome.PASS, null);

    assertThat(run.commit()).isEqualTo("abc123");
    assertThat(run.timestamp()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    assertThat(run.failed()).isFalse();
  }

  @Test
  void failuresCarryAHashAndPassesMustNot() {
    TestRun failure =
        new TestRun(ID, BUILD, "main", "", 1, Duration.ZERO, Outcome.ERROR, "deadbeefdeadbeef");

    assertThat(failure.failed()).isTrue();
    assertThatThrownBy(
            () -> new TestRun(ID, BUILD, "main", "", 0, Duration.ZERO, Outcome.PASS, "deadbeef"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TestRun(ID, BUILD, "main", "", -1, Duration.ZERO, Outcome.PASS, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void outcomeKnowsWhichValuesAreFailures() {
    assertThat(Outcome.FAIL.isFailure()).isTrue();
    assertThat(Outcome.ERROR.isFailure()).isTrue();
    assertThat(Outcome.PASS.isFailure()).isFalse();
    assertThat(Outcome.SKIPPED.isFailure()).isFalse();
  }

  @Test
  void buildRunValidatesItsComponents() {
    assertThatThrownBy(() -> new BuildRun("", "abc", 1, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BuildRun("id", "abc", 0, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new BuildRun("id", "abc", 2, Instant.EPOCH).attempt()).isEqualTo(2);
  }
}
