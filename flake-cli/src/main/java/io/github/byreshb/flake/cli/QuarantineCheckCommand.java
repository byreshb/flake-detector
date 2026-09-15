package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.quarantine.QuarantineEntry;
import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code flake quarantine check}: fail when any entry has expired. Intended for CI, separately from
 * {@code flake gate}, so an expired quarantine is caught even on a green build.
 */
@Command(
    name = "check",
    mixinStandardHelpOptions = true,
    description = "Fail if any quarantine entry has expired.")
public final class QuarantineCheckCommand implements Callable<Integer> {

  @Mixin private LedgerOptions ledger;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    LocalDate today = LocalDate.now();
    QuarantineLedger current = ledger.load();
    List<QuarantineEntry> expired = current.expired(today);
    if (expired.isEmpty()) {
      spec.commandLine()
          .getOut()
          .printf("%d quarantine entry(ies), none expired%n", current.size());
      return 0;
    }
    var err = spec.commandLine().getErr();
    err.printf("%d quarantine entry(ies) expired:%n", expired.size());
    for (QuarantineEntry entry : expired) {
      err.printf(
          "  %s expired on %s (owner %s): fix the test, or renew with 'flake quarantine add'%n",
          entry.testId(), entry.expires(), entry.owner());
    }
    return 1;
  }
}
