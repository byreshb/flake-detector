package io.github.byreshb.flake.github;

/** Thrown when {@link Json#parse(String)} is given text that is not valid JSON. */
final class JsonFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong
   */
  JsonFormatException(String message) {
    super(message);
  }
}
