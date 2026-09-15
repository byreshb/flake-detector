package io.github.byreshb.flake.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code flake quarantine}: manage the quarantine ledger. */
@Command(
    name = "quarantine",
    mixinStandardHelpOptions = true,
    description = "Manage the quarantine ledger.",
    subcommands = {
      QuarantineAddCommand.class,
      QuarantineRemoveCommand.class,
      QuarantineListCommand.class,
      QuarantineCheckCommand.class
    })
public final class QuarantineCommand implements Callable<Integer> {

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    spec.commandLine().usage(spec.commandLine().getErr());
    return 2;
  }
}
