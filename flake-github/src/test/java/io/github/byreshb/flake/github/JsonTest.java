package io.github.byreshb.flake.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void parsesScalars() {
    assertThat(Json.parse("\"hi\"")).isEqualTo("hi");
    assertThat(Json.parse("42")).isEqualTo(42.0);
    assertThat(Json.parse("-3.5")).isEqualTo(-3.5);
    assertThat(Json.parse("1.5e2")).isEqualTo(150.0);
    assertThat(Json.parse("1E+2")).isEqualTo(100.0);
    assertThat(Json.parse("true")).isEqualTo(Boolean.TRUE);
    assertThat(Json.parse("false")).isEqualTo(Boolean.FALSE);
    assertThat(Json.parse("null")).isNull();
  }

  @Test
  void parsesEscapesIncludingUnicode() {
    assertThat(Json.parse("\"a\\nb\\t\\\"c\\\\d\"")).isEqualTo("a\nb\t\"c\\d");
    assertThat(Json.parse("\"\\u00e9\"")).isEqualTo("\u00e9");
  }

  @Test
  void parsesNestedObjectsAndArrays() {
    Object parsed =
        Json.parse("  {\"a\": 1, \"b\": [1, 2.5, \"x\", null, true], \"c\": {\"d\": []}}  ");

    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) parsed;
    assertThat(map.get("a")).isEqualTo(1.0);
    @SuppressWarnings("unchecked")
    List<Object> list = (List<Object>) map.get("b");
    assertThat(list).containsExactly(1.0, 2.5, "x", null, Boolean.TRUE);
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) map.get("c");
    assertThat((List<Object>) nested.get("d")).isEmpty();
  }

  @Test
  void rejectsMalformedInput() {
    assertThatThrownBy(() -> Json.parse("{")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("[1,]")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("\"unterminated")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("truthy")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("nul")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("\"\\x\"")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("\"\\u12\"")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("{\"a\":1")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("42 43")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("-")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("")).isInstanceOf(JsonFormatException.class);
    assertThatThrownBy(() -> Json.parse("{,}")).isInstanceOf(JsonFormatException.class);
  }
}
