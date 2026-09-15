package io.github.byreshb.flake.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal recursive-descent JSON reader, just enough to read GitHub's REST responses without
 * pulling in a JSON library: objects become {@code Map<String, Object>}, arrays {@code
 * List<Object>}, and values are {@code String}, {@code Double}, {@code Boolean} or {@code null}.
 */
final class Json {

  private final String text;
  private int pos;

  private Json(String text) {
    this.text = text;
  }

  /**
   * Parses a JSON document.
   *
   * @param text the document
   * @return the parsed value: a {@code Map}, a {@code List}, a {@code String}, a {@code Double}, a
   *     {@code Boolean} or {@code null}
   * @throws JsonFormatException when the text is not valid JSON
   */
  static Object parse(String text) {
    Json parser = new Json(text);
    parser.skipWhitespace();
    Object value = parser.readValue();
    parser.skipWhitespace();
    if (parser.pos != text.length()) {
      throw new JsonFormatException("unexpected trailing content at position " + parser.pos);
    }
    return value;
  }

  private Object readValue() {
    if (pos >= text.length()) {
      throw new JsonFormatException("unexpected end of input");
    }
    char c = text.charAt(pos);
    return switch (c) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't', 'f' -> readBoolean();
      case 'n' -> readNull();
      default -> readNumber();
    };
  }

  private Map<String, Object> readObject() {
    expect('{');
    Map<String, Object> object = new LinkedHashMap<>();
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return object;
    }
    while (true) {
      skipWhitespace();
      String key = readString();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      object.put(key, readValue());
      skipWhitespace();
      char next = expectOneOf(',', '}');
      if (next == '}') {
        return object;
      }
    }
  }

  private List<Object> readArray() {
    expect('[');
    List<Object> array = new ArrayList<>();
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return array;
    }
    while (true) {
      skipWhitespace();
      array.add(readValue());
      skipWhitespace();
      char next = expectOneOf(',', ']');
      if (next == ']') {
        return array;
      }
    }
  }

  private String readString() {
    expect('"');
    StringBuilder value = new StringBuilder();
    while (true) {
      if (pos >= text.length()) {
        throw new JsonFormatException("unterminated string");
      }
      char c = text.charAt(pos++);
      if (c == '"') {
        return value.toString();
      }
      if (c == '\\') {
        value.append(readEscape());
      } else {
        value.append(c);
      }
    }
  }

  private char readEscape() {
    if (pos >= text.length()) {
      throw new JsonFormatException("unterminated escape");
    }
    char c = text.charAt(pos++);
    return switch (c) {
      case '"' -> '"';
      case '\\' -> '\\';
      case '/' -> '/';
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> readUnicodeEscape();
      default -> throw new JsonFormatException("invalid escape '\\" + c + "'");
    };
  }

  private char readUnicodeEscape() {
    if (pos + 4 > text.length()) {
      throw new JsonFormatException("truncated unicode escape");
    }
    String hex = text.substring(pos, pos + 4);
    pos += 4;
    try {
      return (char) Integer.parseInt(hex, 16);
    } catch (NumberFormatException e) {
      throw new JsonFormatException("invalid unicode escape '\\u" + hex + "'");
    }
  }

  private Boolean readBoolean() {
    if (text.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (text.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    throw new JsonFormatException("invalid literal at position " + pos);
  }

  private Object readNull() {
    if (text.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    throw new JsonFormatException("invalid literal at position " + pos);
  }

  private Double readNumber() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
      pos++;
    }
    if (pos < text.length() && text.charAt(pos) == '.') {
      pos++;
      while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
        pos++;
      }
    }
    if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
      pos++;
      if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
        pos++;
      }
      while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
        pos++;
      }
    }
    if (pos == start) {
      throw new JsonFormatException("invalid value at position " + pos);
    }
    try {
      return Double.parseDouble(text.substring(start, pos));
    } catch (NumberFormatException e) {
      throw new JsonFormatException("invalid number '" + text.substring(start, pos) + "'");
    }
  }

  private void skipWhitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw new JsonFormatException("unexpected end of input");
    }
    return text.charAt(pos);
  }

  private void expect(char c) {
    if (peek() != c) {
      throw new JsonFormatException("expected '" + c + "' at position " + pos);
    }
    pos++;
  }

  private char expectOneOf(char a, char b) {
    char c = peek();
    if (c != a && c != b) {
      throw new JsonFormatException("expected '" + a + "' or '" + b + "' at position " + pos);
    }
    pos++;
    return c;
  }
}
