package io.github.byreshb.flake.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Runs every case in {@code conformance/scoring.json} through the scorer. The same file pins the
 * TypeScript implementation in {@code action/}.
 */
class ScoringConformanceTest {

  static final Path FIXTURE = Path.of("../conformance/scoring.json");
  static final TestId ID = TestId.parse("conformance.Suite#case");

  @TestFactory
  Stream<DynamicTest> conformance() throws IOException {
    Map<String, Object> fixture = load();
    Map<String, Object> weights = cast(fixture.get("weights"));
    FlakinessScorer scorer =
        new FlakinessScorer(
            new Weights(
                number(weights.get("rerunRecovery")),
                number(weights.get("flipRate")),
                number(weights.get("entropy"))));
    assertThat(number(fixture.get("z"))).isEqualTo(Wilson.Z);
    List<Map<String, Object>> cases = cast(fixture.get("cases"));
    return cases.stream()
        .map(c -> DynamicTest.dynamicTest((String) c.get("name"), () -> check(scorer, c)));
  }

  static void check(FlakinessScorer scorer, Map<String, Object> testCase) {
    List<Map<String, Object>> runs = cast(testCase.get("runs"));
    FlakeScore score = scorer.score(ID, runs(runs));
    Map<String, Object> expected = cast(testCase.get("expected"));
    assertThat(score.runs()).isEqualTo(integer(expected.get("runs")));
    assertThat(score.failures()).isEqualTo(integer(expected.get("failures")));
    assertThat(score.flipPairs()).isEqualTo(integer(expected.get("flipPairs")));
    assertThat(score.flips()).isEqualTo(integer(expected.get("flips")));
    assertThat(score.recoveredFailures()).isEqualTo(integer(expected.get("recoveredFailures")));
    assertThat(score.distinctMessages()).isEqualTo(integer(expected.get("distinctMessages")));
    close(score.flipRate(), expected, "flipRate");
    close(score.flipRateInterval().lower(), expected, "flipRateLower");
    close(score.flipRateInterval().upper(), expected, "flipRateUpper");
    close(score.rerunRecoveryRate(), expected, "rerunRecoveryRate");
    close(score.rerunRecoveryInterval().lower(), expected, "rerunRecoveryLower");
    close(score.entropy(), expected, "entropy");
    close(score.entropyComponent(), expected, "entropyComponent");
    close(score.runnerCorrelation(), expected, "runnerCorrelation");
    close(score.hourCorrelation(), expected, "hourCorrelation");
    close(score.score(), expected, "score");
  }

  private static void close(double actual, Map<String, Object> expected, String key) {
    assertThat(actual).as(key).isCloseTo(number(expected.get(key)), within(1e-6));
  }

  static List<TestRun> runs(List<Map<String, Object>> runs) {
    List<TestRun> result = new ArrayList<>();
    for (Map<String, Object> run : runs) {
      BuildRun build =
          new BuildRun(
              (String) run.get("build"),
              (String) run.get("commit"),
              integer(run.get("attempt")),
              Instant.parse((String) run.get("timestamp")));
      result.add(
          new TestRun(
              ID,
              build,
              "main",
              (String) run.get("runner"),
              integer(run.get("rerun")),
              Duration.ZERO,
              Outcome.valueOf((String) run.get("outcome")),
              (String) run.get("hash")));
    }
    return result;
  }

  static Map<String, Object> load() throws IOException {
    try (InputStream in = Files.newInputStream(FIXTURE)) {
      return new Yaml().load(in);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T cast(Object o) {
    return (T) o;
  }

  private static double number(Object o) {
    return ((Number) o).doubleValue();
  }

  private static int integer(Object o) {
    return ((Number) o).intValue();
  }
}
