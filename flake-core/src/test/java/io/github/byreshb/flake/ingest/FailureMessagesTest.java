package io.github.byreshb.flake.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureMessagesTest {

  @Test
  void normalisationCollapsesWhitespaceAndNumbers() {
    assertThat(FailureMessages.normalise("java.lang.AssertionError", "expected  5\n but was 7"))
        .isEqualTo("java.lang.AssertionError: expected # but was #");
    assertThat(FailureMessages.normalise(null, " timeout after 30000 ms "))
        .isEqualTo("timeout after # ms");
    assertThat(FailureMessages.normalise("x.Y", null)).isEqualTo("x.Y");
    assertThat(FailureMessages.normalise(null, null)).isEmpty();
  }

  @Test
  void hashIsStableForEquivalentMessagesAndDifferentOtherwise() {
    String a = FailureMessages.hash("t", "expected 5 but was 7");
    String b = FailureMessages.hash("t", "expected 5  but was 9");
    String c = FailureMessages.hash("t", "connection refused");

    assertThat(a).hasSize(16).matches("[0-9a-f]+").isEqualTo(b);
    assertThat(c).isNotEqualTo(a);
  }
}
