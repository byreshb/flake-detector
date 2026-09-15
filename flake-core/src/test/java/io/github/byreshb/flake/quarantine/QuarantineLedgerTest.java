package io.github.byreshb.flake.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.byreshb.flake.model.TestId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarantineLedgerTest {

  private static final TestId COUPON = TestId.parse("com.acme.CheckoutTest#appliesCoupon");
  private static final TestId SEARCH = TestId.parse("com.acme.SearchTest#findsNothing");
  private static final LocalDate ADDED = LocalDate.of(2026, 9, 14);
  private static final QuarantineEntry COUPON_ENTRY =
      new QuarantineEntry(
          COUPON,
          "timing on CI",
          "byresh",
          ADDED,
          LocalDate.of(2026, 12, 1),
          "https://github.com/acme/shop/issues/42");
  private static final QuarantineEntry SEARCH_ENTRY =
      new QuarantineEntry(SEARCH, "index warm-up", "sam", ADDED, ADDED.plusDays(30), null);

  @TempDir Path tmp;

  @Test
  void roundTripsThroughYaml() {
    Path file = tmp.resolve(".flake/quarantine.yaml");
    QuarantineLedger ledger = QuarantineLedger.empty().add(SEARCH_ENTRY).add(COUPON_ENTRY);

    ledger.save(file);
    QuarantineLedger loaded = QuarantineLedger.load(file);

    assertThat(loaded.entries()).containsExactly(COUPON_ENTRY, SEARCH_ENTRY);
    assertThat(loaded.toYaml()).isEqualTo(ledger.toYaml());
    assertThat(ledger.toYaml())
        .startsWith("# Quarantined tests.")
        .contains(
            "entries:\n"
                + "  - test: com.acme.CheckoutTest#appliesCoupon\n"
                + "    reason: timing on CI\n"
                + "    owner: byresh\n"
                + "    added: 2026-09-14\n"
                + "    expires: 2026-12-01\n"
                + "    issue: https://github.com/acme/shop/issues/42\n"
                + "  - test: com.acme.SearchTest#findsNothing\n");
    assertThat(ledger.toYaml()).doesNotContain("issue: null");
  }

  @Test
  void missingFileIsAnEmptyLedger() {
    QuarantineLedger ledger = QuarantineLedger.load(tmp.resolve("none.yaml"));

    assertThat(ledger.size()).isZero();
    assertThat(ledger.entries()).isEmpty();
    assertThat(QuarantineLedger.load(write("")).size()).isZero();
    assertThat(QuarantineLedger.load(write("entries:\n")).size()).isZero();
    assertThat(QuarantineLedger.empty().toYaml()).endsWith("entries: []\n");
  }

  @Test
  void quotesScalarsThatYamlWouldOtherwiseReinterpret() {
    assertThat(QuarantineLedger.scalar("timing on CI")).isEqualTo("timing on CI");
    assertThat(QuarantineLedger.scalar("com.acme.A#b(String)[1]"))
        .isEqualTo("com.acme.A#b(String)[1]");
    assertThat(QuarantineLedger.scalar("https://x/y")).isEqualTo("https://x/y");
    assertThat(QuarantineLedger.scalar("see: issue")).isEqualTo("\"see: issue\"");
    assertThat(QuarantineLedger.scalar("true")).isEqualTo("\"true\"");
    assertThat(QuarantineLedger.scalar("42")).isEqualTo("\"42\"");
    assertThat(QuarantineLedger.scalar("2026-01-01 later")).isEqualTo("\"2026-01-01 later\"");
    assertThat(QuarantineLedger.scalar("- dash")).isEqualTo("\"- dash\"");
    assertThat(QuarantineLedger.scalar("say \"hi\" \\ tab\tnew\nline"))
        .isEqualTo("\"say \\\"hi\\\" \\\\ tab\\tnew\\nline\"");
    assertThat(QuarantineLedger.scalar("")).isEqualTo("\"\"");
    QuarantineEntry odd =
        new QuarantineEntry(COUPON, "see: #1 \"quoted\"", "o'neil", ADDED, ADDED.plusDays(2), null);
    QuarantineLedger loaded = parse(QuarantineLedger.of(List.of(odd)).toYaml());
    assertThat(loaded.entries()).containsExactly(odd);
  }

  @Test
  void acceptsQuotedAndUnquotedDates() {
    QuarantineLedger ledger =
        parse(
            "entries:\n"
                + "  - test: com.acme.A#b\n"
                + "    reason: r\n"
                + "    owner: o\n"
                + "    added: '2026-09-14'\n"
                + "    expires: 2026-10-14\n");

    assertThat(ledger.entries().get(0).added()).isEqualTo(LocalDate.of(2026, 9, 14));
    assertThat(ledger.entries().get(0).expires()).isEqualTo(LocalDate.of(2026, 10, 14));
  }

  @Test
  void findsExpiredEntriesAndAnswersIsQuarantined() {
    QuarantineLedger ledger = QuarantineLedger.of(List.of(COUPON_ENTRY, SEARCH_ENTRY));

    assertThat(ledger.expired(LocalDate.of(2026, 10, 13))).isEmpty();
    assertThat(ledger.expired(LocalDate.of(2026, 10, 14))).containsExactly(SEARCH_ENTRY);
    assertThat(ledger.expired(LocalDate.of(2027, 1, 1)))
        .containsExactly(COUPON_ENTRY, SEARCH_ENTRY);
    assertThat(ledger.isQuarantined(SEARCH, LocalDate.of(2026, 10, 13))).isTrue();
    assertThat(ledger.isQuarantined(SEARCH, LocalDate.of(2026, 10, 14))).isFalse();
    assertThat(ledger.isQuarantined(TestId.parse("a.B#c"), ADDED)).isFalse();
    assertThat(ledger.find(COUPON)).contains(COUPON_ENTRY);
    assertThat(COUPON_ENTRY.issue()).contains("https://github.com/acme/shop/issues/42");
    assertThat(SEARCH_ENTRY.issue()).isEmpty();
  }

  @Test
  void addReplacesAndRemoveDrops() {
    QuarantineLedger ledger = QuarantineLedger.of(List.of(COUPON_ENTRY));
    QuarantineEntry replacement =
        new QuarantineEntry(
            COUPON, "still flaky", "byresh", ADDED.plusDays(1), ADDED.plusDays(40), null);

    QuarantineLedger replaced = ledger.add(replacement);
    QuarantineLedger removed = replaced.remove(COUPON).remove(SEARCH);

    assertThat(replaced.entries()).containsExactly(replacement);
    assertThat(ledger.entries()).containsExactly(COUPON_ENTRY);
    assertThat(removed.size()).isZero();
  }

  @Test
  void entriesAreValidated() {
    assertThatThrownBy(() -> new QuarantineEntry(COUPON, " ", "o", ADDED, ADDED.plusDays(1), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
    assertThatThrownBy(() -> new QuarantineEntry(COUPON, "r", "", ADDED, ADDED.plusDays(1), null))
        .hasMessageContaining("owner");
    assertThatThrownBy(() -> new QuarantineEntry(COUPON, "r", "o", ADDED, ADDED, null))
        .hasMessageContaining("must be after");
    assertThatThrownBy(() -> new QuarantineEntry(COUPON, "r", "o", ADDED, ADDED.plusDays(91), null))
        .hasMessageContaining("more than 90 days");
    assertThat(new QuarantineEntry(COUPON, "r", "o", ADDED, ADDED.plusDays(90), " ").issueUrl())
        .isNull();
    assertThatThrownBy(() -> QuarantineLedger.of(List.of(COUPON_ENTRY, COUPON_ENTRY)))
        .isInstanceOf(LedgerFormatException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void rejectsMalformedFilesWithTheFileNameAndEntryIndex() {
    assertThatThrownBy(
            () ->
                QuarantineLedger.load(
                    write(
                        "entries:\n"
                            + "  - test: com.acme.A#b\n"
                            + "    reason: r\n"
                            + "    owner: o\n"
                            + "    added: 2026-09-14\n")))
        .isInstanceOf(LedgerFormatException.class)
        .hasMessageContaining("bad.yaml")
        .hasMessageContaining("entry 0: missing 'expires'");
    assertThatThrownBy(
            () ->
                parse(
                    "entries:\n"
                        + "  - test: com.acme.A#b\n"
                        + "    reason: r\n"
                        + "    owner: o\n"
                        + "    added: 2026-09-14\n"
                        + "    expires: soon\n"))
        .hasMessageContaining("entry 0: expires must be a date");
    assertThatThrownBy(
            () ->
                parse(
                    "entries:\n"
                        + "  - test: com.acme.A#b\n"
                        + "    reason: r\n"
                        + "    owner: o\n"
                        + "    added: 2026-09-14\n"
                        + "    expires: 2027-09-14\n"))
        .hasMessageContaining("more than 90 days");
    assertThatThrownBy(
            () ->
                parse(
                    "entries:\n"
                        + "  - test: nohash\n"
                        + "    reason: r\n"
                        + "    owner: o\n"
                        + "    added: 2026-09-14\n"
                        + "    expires: 2026-10-14\n"))
        .hasMessageContaining("className#methodName");
    assertThatThrownBy(() -> parse("- a\n- b\n")).hasMessageContaining("mapping");
    assertThatThrownBy(() -> parse("entries: 3\n")).hasMessageContaining("must be a list");
    assertThatThrownBy(() -> parse("entries:\n  - 3\n"))
        .hasMessageContaining("entry 0 must be a mapping");
    assertThatThrownBy(() -> parse("entries: [\n")).hasMessageContaining("not valid YAML");
    assertThatThrownBy(() -> parse("entries: !!java.io.File x\n"))
        .isInstanceOf(LedgerFormatException.class);
  }

  private Path write(String content) {
    try {
      Path file = tmp.resolve("bad.yaml");
      Files.writeString(file, content);
      return file;
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static QuarantineLedger parse(String yaml) {
    return QuarantineLedger.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }
}
