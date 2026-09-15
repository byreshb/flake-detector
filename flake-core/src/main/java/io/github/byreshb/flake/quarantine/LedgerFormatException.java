package io.github.byreshb.flake.quarantine;

/** Thrown when the ledger file cannot be parsed or an entry in it is invalid. */
public class LedgerFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong
   */
  public LedgerFormatException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what is wrong
   * @param cause the underlying error
   */
  public LedgerFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
