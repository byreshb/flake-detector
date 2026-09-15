package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.quarantine.QuarantineEntry;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code flake quarantine add}: add or replace an entry in the ledger. */
@Command(
    name = "add",
    mixinStandardHelpOptions = true,
    description = "Add or replace a quarantine entry.")
public final class QuarantineAddCommand implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "TEST", description = "Test id, className#methodName.")
  private String testId;

  @Option(
      names = "--reason",
      required = true,
      paramLabel = "TEXT",
      description = "Why the test is quarantined.")
  private String reason;

  @Option(
      names = "--owner",
      required = true,
      paramLabel = "NAME",
      description = "Who is responsible for it.")
  private String owner;

  @Option(
      names = "--expires",
      required = true,
      paramLabel = "DATE",
      description = "Expiry date (ISO-8601, e.g. 2026-12-01), at most 90 days after --added.")
  private LocalDate expires;

  @Option(
      names = "--added",
      paramLabel = "DATE",
      description = "Date the quarantine was added (default: today).")
  private LocalDate added;

  @Option(names = "--issue", paramLabel = "URL", description = "Tracking issue.")
  private String issue;

  @Mixin private LedgerOptions ledger;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    LocalDate addedDate = added != null ? added : LocalDate.now();
    QuarantineEntry entry =
        new QuarantineEntry(TestId.parse(testId), reason, owner, addedDate, expires, issue);
    ledger.save(ledger.load().add(entry));
    long daysFromNow = ChronoUnit.DAYS.between(LocalDate.now(), entry.expires());
    spec.commandLine()
        .getOut()
        .printf(
            "Quarantined %s (owner %s, expires %s, %d day(s) from now) in %s%n",
            entry.testId(), entry.owner(), entry.expires(), daysFromNow, ledger.file());
    return 0;
  }
}
