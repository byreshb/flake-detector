package io.github.byreshb.flake.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TestIdTest {

  @Test
  void parsesAndPrintsTheTextualForm() {
    TestId id = TestId.parse("com.acme.CheckoutTest#appliesCoupon");

    assertThat(id.className()).isEqualTo("com.acme.CheckoutTest");
    assertThat(id.methodName()).isEqualTo("appliesCoupon");
    assertThat(id.simpleClassName()).isEqualTo("CheckoutTest");
    assertThat(id).hasToString("com.acme.CheckoutTest#appliesCoupon");
  }

  @Test
  void keepsParameterIndexInMethodName() {
    TestId id = TestId.parse("com.acme.SearchTest#findsByName(String)[2]");

    assertThat(id.methodName()).isEqualTo("findsByName(String)[2]");
  }

  @Test
  void rejectsTextWithoutSeparatorOrWithEmptySides() {
    assertThatThrownBy(() -> TestId.parse("com.acme.CheckoutTest"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TestId.parse("#method")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TestId.parse("com.acme.CheckoutTest#"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TestId(" ", "m")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ordersByClassThenMethod() {
    TestId a = TestId.parse("a.A#z");
    TestId b = TestId.parse("a.B#a");
    TestId c = TestId.parse("a.B#b");

    assertThat(a).isLessThan(b);
    assertThat(b).isLessThan(c);
    assertThat(c.compareTo(c)).isZero();
  }
}
