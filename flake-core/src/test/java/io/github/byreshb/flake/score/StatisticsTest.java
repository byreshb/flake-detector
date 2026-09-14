package io.github.byreshb.flake.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.byreshb.flake.score.CramersV.Observation;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticsTest {

  @Test
  void wilsonIntervalNarrowsWithEvidence() {
    Wilson one = Wilson.of(1, 1);
    Wilson many = Wilson.of(300, 300);
    Wilson half = Wilson.of(50, 100);

    assertThat(one.lower()).isCloseTo(0.2065, within(1e-3));
    assertThat(one.upper()).isCloseTo(1.0, within(1e-9));
    assertThat(many.lower()).isGreaterThan(0.98);
    assertThat(half.lower()).isCloseTo(0.4038, within(1e-3));
    assertThat(half.upper()).isCloseTo(0.5962, within(1e-3));
    assertThat(Wilson.of(0, 0)).isEqualTo(Wilson.NONE);
    assertThat(Wilson.of(0, 10).lower()).isZero();
    assertThatThrownBy(() -> Wilson.of(2, 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cramersVIsOneWhenTheLabelDeterminesTheOutcome() {
    List<Observation> perfect =
        List.of(
            new Observation(true, "mac"),
            new Observation(true, "mac"),
            new Observation(false, "linux"),
            new Observation(false, "linux"));

    assertThat(CramersV.of(perfect)).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void cramersVIsZeroWithoutAssociationOrVariation() {
    List<Observation> independent =
        List.of(
            new Observation(true, "mac"),
            new Observation(false, "mac"),
            new Observation(true, "linux"),
            new Observation(false, "linux"));

    assertThat(CramersV.of(independent)).isZero();
    assertThat(CramersV.of(List.of())).isZero();
    assertThat(CramersV.of(List.of(new Observation(true, "a"), new Observation(false, "a"))))
        .isZero();
    assertThat(CramersV.of(List.of(new Observation(true, "a"), new Observation(true, "b"))))
        .isZero();
  }

  @Test
  void cramersVHandlesMoreThanTwoLabels() {
    List<Observation> skewed =
        List.of(
            new Observation(true, "a"),
            new Observation(true, "a"),
            new Observation(false, "b"),
            new Observation(false, "c"),
            new Observation(true, "c"));

    assertThat(CramersV.of(skewed)).isBetween(0.5, 0.8);
  }
}
