package io.github.byreshb.flake.model;

import java.util.Objects;

/**
 * Identifies a test by its fully qualified class name and method name, written {@code
 * com.acme.CheckoutTest#appliesCoupon}.
 *
 * @param className fully qualified class name, never blank
 * @param methodName method name as reported by the test framework (may include a parameter index
 *     such as {@code applies(String)[2]}), never blank
 */
public record TestId(String className, String methodName) implements Comparable<TestId> {

  /** Separator between class and method in the textual form. */
  public static final char SEPARATOR = '#';

  /**
   * Validates the components.
   *
   * @param className fully qualified class name
   * @param methodName method name
   */
  public TestId {
    Objects.requireNonNull(className, "className");
    Objects.requireNonNull(methodName, "methodName");
    if (className.isBlank() || methodName.isBlank()) {
      throw new IllegalArgumentException("class and method must not be blank");
    }
  }

  /**
   * Parses the textual form {@code className#methodName}.
   *
   * @param text the textual form
   * @return the parsed id
   * @throws IllegalArgumentException when the text has no {@code #} or an empty side
   */
  public static TestId parse(String text) {
    Objects.requireNonNull(text, "text");
    int at = text.indexOf(SEPARATOR);
    if (at <= 0 || at == text.length() - 1) {
      throw new IllegalArgumentException("expected className#methodName but got '" + text + "'");
    }
    return new TestId(text.substring(0, at), text.substring(at + 1));
  }

  /**
   * The simple class name, without the package.
   *
   * @return the part of the class name after the last dot
   */
  public String simpleClassName() {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  @Override
  public int compareTo(TestId other) {
    int c = className.compareTo(other.className);
    return c != 0 ? c : methodName.compareTo(other.methodName);
  }

  @Override
  public String toString() {
    return className + SEPARATOR + methodName;
  }
}
