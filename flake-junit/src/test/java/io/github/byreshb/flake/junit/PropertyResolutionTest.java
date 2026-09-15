package io.github.byreshb.flake.junit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PropertyResolutionTest {

  @AfterEach
  void clearProperties() {
    System.clearProperty(QuarantineExtension.MODE_PROPERTY);
    System.clearProperty(QuarantineExtension.LEDGER_PROPERTY);
    System.clearProperty(QuarantineExtension.OBSERVED_DIR_PROPERTY);
  }

  @Test
  void defaultsWhenNoPropertyIsSet() {
    assertThat(QuarantineExtension.modeFromProperty()).isEqualTo(QuarantineMode.OBSERVE);
    assertThat(QuarantineExtension.ledgerPathFromProperty())
        .isEqualTo(QuarantineLedger.DEFAULT_PATH);
    assertThat(QuarantineExtension.observedDirFromProperty()).isEqualTo(Path.of(".flake/observed"));
  }

  @Test
  void readsSkipModeCaseInsensitively() {
    System.setProperty(QuarantineExtension.MODE_PROPERTY, "SKIP");
    assertThat(QuarantineExtension.modeFromProperty()).isEqualTo(QuarantineMode.SKIP);
  }

  @Test
  void anyOtherModeValueIsObserve() {
    System.setProperty(QuarantineExtension.MODE_PROPERTY, "bogus");
    assertThat(QuarantineExtension.modeFromProperty()).isEqualTo(QuarantineMode.OBSERVE);
  }

  @Test
  void ledgerAndObservedDirAreOverridable() {
    System.setProperty(QuarantineExtension.LEDGER_PROPERTY, "custom/ledger.yaml");
    System.setProperty(QuarantineExtension.OBSERVED_DIR_PROPERTY, "custom/observed");

    assertThat(QuarantineExtension.ledgerPathFromProperty())
        .isEqualTo(Path.of("custom/ledger.yaml"));
    assertThat(QuarantineExtension.observedDirFromProperty()).isEqualTo(Path.of("custom/observed"));
  }
}
