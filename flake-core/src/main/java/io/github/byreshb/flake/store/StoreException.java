package io.github.byreshb.flake.store;

/** Thrown when the database cannot be opened, migrated, read or written. */
public class StoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the underlying error
   */
  public StoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
