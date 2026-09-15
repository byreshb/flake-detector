package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code flake quarantine remove}: drop an entry from the ledger. */
@Command(
    name = "remove",
    mixinStandardHelpOptions = true,
    description = "Remove a quarantine entry.")
public final class QuarantineRemoveCommand implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "TEST", description = "Test id, className#methodName.")
  private String testId;

  @Mixin private LedgerOptions ledger;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    TestId id = TestId.parse(testId);
    QuarantineLedger current = ledger.load();
    if (current.find(id).isEmpty()) {
      spec.commandLine().getErr().printf("%s is not quarantined in %s%n", id, ledger.file());
      return 1;
    }
    ledger.save(current.remove(id));
    spec.commandLine().getOut().printf("Removed %s from %s%n", id, ledger.file());
    return 0;
  }
}
