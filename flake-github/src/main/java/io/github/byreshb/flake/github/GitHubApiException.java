package io.github.byreshb.flake.github;

/** Thrown when the GitHub REST API returns an error, or a response could not be understood. */
public final class GitHubApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int statusCode;

  /**
   * Creates the exception.
   *
   * @param statusCode the HTTP status code, or 0 when there wasn't one (a transport failure)
   * @param message what went wrong
   * @param cause the underlying error, or null
   */
  public GitHubApiException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /**
   * The HTTP status code.
   *
   * @return the code, or 0 when the request never got a response
   */
  public int statusCode() {
    return statusCode;
  }
}
