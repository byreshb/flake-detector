package io.github.byreshb.flake.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlakinessScorerTest {

  private static final TestId FLAKY = TestId.parse("com.acme.CheckoutTest#appliesCoupon");
  private static final TestId STABLE = TestId.parse("com.acme.CheckoutTest#addsItem");
  private static final TestId BROKEN = TestId.parse("com.acme.CheckoutTest#rejectsExpiredCard");
  private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

  private static TestRun run(
      TestId id, String commit, int day, int rerun, Outcome outcome, String hash) {
    BuildRun build = new BuildRun("b" + commit, commit, 1, T0.plus(Duration.ofDays(day)));
    return new TestRun(id, build, "main", "linux", rerun, Duration.ofMillis(5), outcome, hash);
  }

  private static List<TestRun> history() {
    List<TestRun> runs = new ArrayList<>();
    for (int day = 0; day < 6; day++) {
      String commit = "c" + day;
      runs.add(run(STABLE, commit, day, 0, Outcome.PASS, null));
      runs.add(run(BROKEN, commit, day, 0, Outcome.FAIL, "same"));
      if (day % 2 == 0) {
        runs.add(run(FLAKY, commit, day, 0, Outcome.FAIL, "m" + day));
        runs.add(run(FLAKY, commit, day, 1, Outcome.PASS, null));
      } else {
        runs.add(run(FLAKY, commit, day, 0, Outcome.PASS, null));
      }
    }
    return runs;
  }

  @Test
  void ranksTheFlakyTestFirstAndGivesConsistentFailuresZero() {
    try (SqliteRunStore store = SqliteRunStore.inMemory()) {
      store.record(history());

      List<FlakeScore> scores = new FlakinessScorer().scoreAll(store);

      assertThat(scores).extracting(FlakeScore::testId).containsExactly(FLAKY, STABLE, BROKEN);
      assertThat(scores.get(0).score()).isGreaterThan(0.2);
      assertThat(scores.get(1).score()).isZero();
      assertThat(scores.get(2).score()).isZero();
      assertThat(scores.get(2).failures()).isEqualTo(6);
      assertThat(scores.get(2).distinctMessages()).isEqualTo(1);
    }
  }

  @Test
  void scoreOnlyLooksAtTheRequestedTest() {
    FlakeScore score = new FlakinessScorer().score(FLAKY, history());

    assertThat(score.runs()).isEqualTo(9);
    assertThat(score.failures()).isEqualTo(3);
    assertThat(score.recoveredFailures()).isEqualTo(3);
    assertThat(score.flipPairs()).isEqualTo(3);
    assertThat(score.flips()).isEqualTo(3);
    assertThat(score.rerunRecoveryRate()).isEqualTo(1.0);
    assertThat(score.rerunRecoveryInterval().lower()).isCloseTo(0.4385, within(1e-3));
    assertThat(score.entropy()).isCloseTo(1.0, within(1e-9));
    assertThat(score.entropyComponent()).isCloseTo(2.0 / 3, within(1e-9));
    assertThat(score.weights()).isEqualTo(Weights.DEFAULT);
  }

  @Test
  void explainListsEveryComponentWithItsContribution() {
    FlakeScore score = new FlakinessScorer().score(FLAKY, history());

    String explanation = score.explain();

    assertThat(explanation)
        .startsWith("com.acme.CheckoutTest#appliesCoupon: score 0.")
        .contains("rerun recovery")
        .contains("3 of 3 failure(s) passed on a retry of the same commit")
        .contains("x 0.5 =")
        .contains("flip rate")
        .contains("3 of 3 consecutive same-commit pair(s) changed outcome")
        .contains("message entropy")
        .contains("3 distinct message(s) over 3 failure(s)")
        .contains("reported only:   runner correlation 0.00, hour-of-day correlation 0.00");
    FlakeScore.Component component = new FlakeScore.Component("x", "d", 0.5, 0.3);
    assertThat(component.contribution()).isCloseTo(0.15, within(1e-9));
  }

  @Test
  void emptyHistoryScoresZero() {
    FlakeScore score = new FlakinessScorer().score(FLAKY, List.of());

    assertThat(score.score()).isZero();
    assertThat(score.runs()).isZero();
    assertThat(score.flipRateInterval()).isEqualTo(Wilson.NONE);
    assertThat(score.explain()).contains("0 of 0 failure(s)");
  }

  @Test
  void customWeightsChangeTheTotal() {
    FlakeScore score = new FlakinessScorer(new Weights(1, 0, 0)).score(FLAKY, history());

    assertThat(score.score()).isEqualTo(score.rerunRecoveryInterval().lower());
    assertThatThrownBy(() -> new Weights(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rankingBreaksTiesByEvidenceThenName() {
    FlakeScore a = new FlakinessScorer().score(STABLE, history());
    FlakeScore b = new FlakinessScorer().score(BROKEN, history());
    FlakeScore fewer = new FlakinessScorer().score(STABLE, history().subList(0, 4));

    assertThat(FlakinessScorer.RANKING.compare(a, b)).isNegative();
    assertThat(FlakinessScorer.RANKING.compare(a, fewer)).isNegative();
    assertThat(FlakinessScorer.RANKING.compare(a, a)).isZero();
  }
}
