package io.github.byreshb.flake.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Normalises and hashes failure messages so that runs of the same test can be compared by what went
 * wrong without storing the message itself.
 */
public final class FailureMessages {

  private FailureMessages() {}

  /**
   * Normalises a failure message so that incidental differences do not produce distinct hashes: the
   * exception type is prefixed, whitespace is collapsed, and every run of digits is replaced by
   * {@code #} so that {@code expected 5 but was 7} and {@code expected 5 but was 9} are the same
   * message.
   *
   * @param type exception type, may be null
   * @param message message, may be null
   * @return the normalised text, never null
   */
  public static String normalise(String type, String message) {
    String t = type == null ? "" : type.trim();
    String m = message == null ? "" : message.trim();
    String text = t.isEmpty() ? m : m.isEmpty() ? t : t + ": " + m;
    return text.replaceAll("\\s+", " ").replaceAll("\\d+", "#");
  }

  /**
   * Hashes the normalised form of a failure message.
   *
   * @param type exception type, may be null
   * @param message message, may be null
   * @return the first 16 hex characters of the SHA-256 of {@link #normalise(String, String)}
   */
  public static String hash(String type, String message) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(normalise(type, message).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }
}
