package io.github.byreshb.flake.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarantineCommandTest {

  @TempDir Path tmp;

  private String ledgerPath() {
    return tmp.resolve(".flake/quarantine.yaml").toString();
  }

  @Test
  void addsAnEntryAndListsIt() {
    CliTestSupport cli = new CliTestSupport();
    String ledger = ledgerPath();

    int status =
        cli.run(
            "quarantine",
            "add",
            "com.acme.CheckoutTest#appliesCoupon",
            "--ledger",
            ledger,
            "--reason",
            "timing on CI",
            "--owner",
            "byresh",
            "--added",
            "2026-09-14",
            "--expires",
            "2026-12-01",
            "--issue",
            "https://github.com/acme/shop/issues/42");

    assertThat(status).isZero();
    assertThat(cli.out.toString())
        .contains(
            "Quarantined com.acme.CheckoutTest#appliesCoupon (owner byresh, expires 2026-12-01");

    CliTestSupport list = new CliTestSupport();
    assertThat(list.run("quarantine", "list", "--ledger", ledger)).isZero();
    assertThat(list.out.toString())
        .contains(
            "| `com.acme.CheckoutTest#appliesCoupon` | byresh | timing on CI | 2026-09-14 |"
                + " 2026-12-01 |")
        .contains("https://github.com/acme/shop/issues/42");
  }

  @Test
  void listWithNothingQuarantinedSaysSo() {
    CliTestSupport cli = new CliTestSupport();

    assertThat(cli.run("quarantine", "list", "--ledger", ledgerPath())).isZero();

    assertThat(cli.out.toString()).contains("No quarantined tests.");
  }

  @Test
  void removeDropsAnExistingEntryAndFailsOnAMissingOne() {
    String ledger = ledgerPath();
    add(ledger, "com.acme.A#b", "2026-09-14", "2026-10-01");

    CliTestSupport remove = new CliTestSupport();
    assertThat(remove.run("quarantine", "remove", "com.acme.A#b", "--ledger", ledger)).isZero();
    assertThat(remove.out.toString()).contains("Removed com.acme.A#b");
    assertThat(QuarantineLedger.load(Path.of(ledger)).size()).isZero();

    CliTestSupport again = new CliTestSupport();
    assertThat(again.run("quarantine", "remove", "com.acme.A#b", "--ledger", ledger)).isEqualTo(1);
    assertThat(again.err.toString()).contains("com.acme.A#b is not quarantined");
  }

  @Test
  void checkPassesWhenNothingHasExpiredAndFailsWhenSomethingHas() {
    String ledger = ledgerPath();
    add(ledger, "com.acme.A#b", "2026-01-01", "2026-01-10");
    add(ledger, "com.acme.C#d", "2026-09-01", "2026-11-30");

    CliTestSupport cli = new CliTestSupport();
    int status = cli.run("quarantine", "check", "--ledger", ledger);

    assertThat(status).isEqualTo(1);
    assertThat(cli.err.toString())
        .contains("1 quarantine entry(ies) expired:")
        .contains("com.acme.A#b expired on 2026-01-10");
  }

  @Test
  void checkPassesOnAnEmptyLedger() {
    CliTestSupport cli = new CliTestSupport();

    assertThat(cli.run("quarantine", "check", "--ledger", ledgerPath())).isZero();
    assertThat(cli.out.toString()).contains("0 quarantine entry(ies), none expired");
  }

  @Test
  void expiredOnlyFiltersTheList() {
    String ledger = ledgerPath();
    add(ledger, "com.acme.A#b", "2026-01-01", "2026-01-10");
    add(ledger, "com.acme.C#d", "2026-09-01", "2026-11-30");

    CliTestSupport cli = new CliTestSupport();
    cli.run("quarantine", "list", "--ledger", ledger, "--expired-only");

    assertThat(cli.out.toString()).contains("com.acme.A#b").doesNotContain("com.acme.C#d");
  }

  @Test
  void bareQuarantinePrintsUsageAndFails() {
    CliTestSupport cli = new CliTestSupport();

    assertThat(cli.run("quarantine")).isEqualTo(2);
    assertThat(cli.err.toString()).contains("Usage:").contains("quarantine");
  }

  private void add(String ledger, String test, String added, String expires) {
    CliTestSupport cli = new CliTestSupport();
    int status =
        cli.run(
            "quarantine",
            "add",
            test,
            "--ledger",
            ledger,
            "--reason",
            "r",
            "--owner",
            "o",
            "--added",
            added,
            "--expires",
            expires);
    assertThat(status).as(cli.err.toString()).isZero();
  }
}
