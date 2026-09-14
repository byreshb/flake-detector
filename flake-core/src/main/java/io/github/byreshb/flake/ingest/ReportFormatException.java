package io.github.byreshb.flake.ingest;

/** Thrown when a file is not a JUnit XML report or is malformed. */
public class ReportFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong
   */
  public ReportFormatException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what is wrong
   * @param cause the underlying parser error
   */
  public ReportFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
