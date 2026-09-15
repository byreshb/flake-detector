package io.github.byreshb.flake.quarantine;

import io.github.byreshb.flake.model.TestId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The quarantine ledger, by convention {@code .flake/quarantine.yaml} in the repository:
 *
 * <pre>
 * entries:
 *   - test: com.acme.CheckoutTest#appliesCoupon
 *     reason: timing on CI
 *     owner: byresh
 *     added: 2026-09-14
 *     expires: 2026-12-01
 *     issue: https://github.com/acme/shop/issues/42
 * </pre>
 *
 * <p>A ledger is immutable; {@link #add} and {@link #remove} return new ledgers. Entries are kept
 * in test id order and there is at most one entry per test.
 */
public final class QuarantineLedger {

  /** Default location of the ledger, relative to the project root. */
  public static final Path DEFAULT_PATH = Path.of(".flake", "quarantine.yaml");

  private static final String HEADER =
      "# Quarantined tests. Every entry needs an owner, a reason and an expiry at most "
          + QuarantineEntry.MAX_DAYS
          + " days\n"
          + "# after it was added; expired entries fail the build. See docs/quarantine.md.\n";

  private final Map<TestId, QuarantineEntry> entries;

  private QuarantineLedger(Map<TestId, QuarantineEntry> entries) {
    this.entries = entries;
  }

  /**
   * An empty ledger.
   *
   * @return a ledger with no entries
   */
  public static QuarantineLedger empty() {
    return new QuarantineLedger(Map.of());
  }

  /**
   * A ledger with the given entries.
   *
   * @param entries the entries; at most one per test
   * @return the ledger, sorted by test id
   * @throws LedgerFormatException when a test appears twice
   */
  public static QuarantineLedger of(List<QuarantineEntry> entries) {
    Map<TestId, QuarantineEntry> map = new TreeMap<>();
    for (QuarantineEntry entry : entries) {
      if (map.putIfAbsent(entry.testId(), entry) != null) {
        throw new LedgerFormatException("duplicate entry for " + entry.testId());
      }
    }
    return new QuarantineLedger(Collections.unmodifiableMap(map));
  }

  /**
   * Loads the ledger. A missing file is an empty ledger.
   *
   * @param file the YAML file
   * @return the ledger
   * @throws LedgerFormatException when the file is not a valid ledger
   * @throws UncheckedIOException when the file exists but cannot be read
   */
  public static QuarantineLedger load(Path file) {
    Objects.requireNonNull(file, "file");
    if (!Files.exists(file)) {
      return empty();
    }
    try (InputStream in = Files.newInputStream(file)) {
      return parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    } catch (LedgerFormatException e) {
      throw new LedgerFormatException(file + ": " + e.getMessage(), e);
    }
  }

  /**
   * Parses a ledger from YAML.
   *
   * @param in the YAML content; not closed
   * @return the ledger
   * @throws LedgerFormatException when the content is not a valid ledger
   */
  public static QuarantineLedger parse(InputStream in) {
    Object document;
    try {
      document = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    } catch (RuntimeException e) {
      throw new LedgerFormatException("not valid YAML: " + e.getMessage(), e);
    }
    if (document == null) {
      return empty();
    }
    if (!(document instanceof Map<?, ?> root)) {
      throw new LedgerFormatException("expected a mapping with an 'entries' list at the top");
    }
    Object list = root.get("entries");
    if (list == null) {
      return empty();
    }
    if (!(list instanceof List<?> items)) {
      throw new LedgerFormatException("'entries' must be a list");
    }
    List<QuarantineEntry> entries = new ArrayList<>();
    int index = 0;
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> fields)) {
        throw new LedgerFormatException("entry " + index + " must be a mapping");
      }
      entries.add(entry(fields, index));
      index++;
    }
    return of(entries);
  }

  private static QuarantineEntry entry(Map<?, ?> fields, int index) {
    String prefix = "entry " + index + ": ";
    try {
      TestId testId = TestId.parse(required(fields, "test", prefix));
      return new QuarantineEntry(
          testId,
          required(fields, "reason", prefix),
          required(fields, "owner", prefix),
          date(fields.get("added"), prefix + "added"),
          date(fields.get("expires"), prefix + "expires"),
          optional(fields, "issue"));
    } catch (IllegalArgumentException e) {
      throw new LedgerFormatException(prefix + e.getMessage(), e);
    }
  }

  private static String required(Map<?, ?> fields, String key, String prefix) {
    Object value = fields.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new LedgerFormatException(prefix + "missing '" + key + "'");
    }
    return value.toString().trim();
  }

  private static String optional(Map<?, ?> fields, String key) {
    Object value = fields.get(key);
    return value == null ? null : value.toString().trim();
  }

  private static LocalDate date(Object value, String what) {
    if (value == null || value.toString().isBlank()) {
      throw new LedgerFormatException(
          what.replace(": added", ": missing 'added'").replace(": expires", ": missing 'expires'"));
    }
    if (value instanceof Date date) {
      return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }
    try {
      return LocalDate.parse(value.toString().trim());
    } catch (DateTimeParseException e) {
      throw new LedgerFormatException(
          what + " must be a date like 2026-12-01, got '" + value + "'");
    }
  }

  /**
   * Writes the ledger, creating parent directories. The file is always fully rewritten in a
   * canonical layout.
   *
   * @param file where to write
   * @throws UncheckedIOException when the file cannot be written
   */
  public void save(Path file) {
    Objects.requireNonNull(file, "file");
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, toYaml(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  /**
   * The ledger as YAML, in the format {@link #load} reads.
   *
   * @return the document
   */
  public String toYaml() {
    StringBuilder yaml = new StringBuilder(HEADER).append("entries:");
    if (entries.isEmpty()) {
      yaml.append(" []");
    }
    yaml.append('\n');
    for (QuarantineEntry entry : entries.values()) {
      yaml.append("  - test: ").append(scalar(entry.testId().toString())).append('\n');
      yaml.append("    reason: ").append(scalar(entry.reason())).append('\n');
      yaml.append("    owner: ").append(scalar(entry.owner())).append('\n');
      yaml.append("    added: ").append(entry.added()).append('\n');
      yaml.append("    expires: ").append(entry.expires()).append('\n');
      entry.issue().ifPresent(url -> yaml.append("    issue: ").append(scalar(url)).append('\n'));
    }
    return yaml.toString();
  }

  /**
   * Writes a plain scalar when YAML would read it back unchanged, a double-quoted one otherwise.
   */
  static String scalar(String text) {
    boolean plain =
        !text.isEmpty()
            && !text.startsWith(" ")
            && !text.endsWith(" ")
            && "-?:,[]{}#&*!|>'\"%@`".indexOf(text.charAt(0)) < 0
            && !text.contains(": ")
            && !text.contains(" #")
            && !text.contains("\n")
            && !text.matches(
                "(?i)true|false|null|~|yes|no|on|off|[-+]?[0-9.eE_]+|\\d{4}-\\d{2}-\\d{2}.*");
    if (plain) {
      return text;
    }
    StringBuilder quoted = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> quoted.append("\\\"");
        case '\\' -> quoted.append("\\\\");
        case '\n' -> quoted.append("\\n");
        case '\t' -> quoted.append("\\t");
        default -> quoted.append(c);
      }
    }
    return quoted.append('"').toString();
  }

  /**
   * The entries in test id order.
   *
   * @return the entries
   */
  public List<QuarantineEntry> entries() {
    return List.copyOf(entries.values());
  }

  /**
   * The entry for a test.
   *
   * @param testId the test
   * @return the entry if the test is quarantined (expired or not)
   */
  public Optional<QuarantineEntry> find(TestId testId) {
    return Optional.ofNullable(entries.get(testId));
  }

  /**
   * Whether a test is quarantined and the quarantine has not expired.
   *
   * @param testId the test
   * @param today the current date
   * @return true when an unexpired entry exists
   */
  public boolean isQuarantined(TestId testId, LocalDate today) {
    return find(testId).filter(e -> !e.isExpired(today)).isPresent();
  }

  /**
   * The entries whose quarantine has run out.
   *
   * @param today the current date
   * @return expired entries in test id order
   */
  public List<QuarantineEntry> expired(LocalDate today) {
    return entries.values().stream().filter(e -> e.isExpired(today)).toList();
  }

  /**
   * A ledger with this entry added or replaced.
   *
   * @param entry the entry
   * @return the new ledger
   */
  public QuarantineLedger add(QuarantineEntry entry) {
    Map<TestId, QuarantineEntry> copy = new TreeMap<>(entries);
    copy.put(entry.testId(), entry);
    return new QuarantineLedger(Collections.unmodifiableMap(copy));
  }

  /**
   * A ledger without the entry for a test.
   *
   * @param testId the test
   * @return the new ledger; the same entries when the test was not quarantined
   */
  public QuarantineLedger remove(TestId testId) {
    Map<TestId, QuarantineEntry> copy = new TreeMap<>(entries);
    copy.remove(testId);
    return new QuarantineLedger(Collections.unmodifiableMap(copy));
  }

  /**
   * Number of entries.
   *
   * @return the size
   */
  public int size() {
    return entries.size();
  }
}
