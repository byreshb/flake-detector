package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.quarantine.QuarantineEntry;
import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code flake quarantine list}: print the ledger as a table. */
@Command(name = "list", mixinStandardHelpOptions = true, description = "List quarantine entries.")
public final class QuarantineListCommand implements Callable<Integer> {

  @Option(names = "--expired-only", description = "List only entries whose quarantine has run out.")
  private boolean expiredOnly;

  @Mixin private LedgerOptions ledger;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    LocalDate today = LocalDate.now();
    QuarantineLedger current = ledger.load();
    List<QuarantineEntry> entries = expiredOnly ? current.expired(today) : current.entries();
    if (entries.isEmpty()) {
      spec.commandLine()
          .getOut()
          .println(expiredOnly ? "No expired quarantine entries." : "No quarantined tests.");
      return 0;
    }
    var out = spec.commandLine().getOut();
    out.println("| Test | Owner | Reason | Added | Expires | Status | Issue |");
    out.println("|------|-------|--------|-------|---------|--------|-------|");
    for (QuarantineEntry entry : entries) {
      long days = ChronoUnit.DAYS.between(today, entry.expires());
      String status = entry.isExpired(today) ? "expired " + (-days) + "d ago" : days + "d left";
      out.printf(
          "| `%s` | %s | %s | %s | %s | %s | %s |%n",
          entry.testId(),
          entry.owner(),
          entry.reason(),
          entry.added(),
          entry.expires(),
          status,
          entry.issue().orElse(""));
    }
    return 0;
  }
}
