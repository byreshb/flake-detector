package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.quarantine.QuarantineLedger;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/** The {@code --ledger} option shared by every subcommand that touches the quarantine ledger. */
public final class LedgerOptions {

  @Option(
      names = {"--ledger"},
      paramLabel = "FILE",
      description = "Quarantine ledger (default: ${DEFAULT-VALUE}).",
      defaultValue = ".flake/quarantine.yaml")
  private Path file;

  /**
   * Loads the ledger. A missing file is an empty ledger.
   *
   * @return the ledger
   */
  public QuarantineLedger load() {
    return QuarantineLedger.load(file);
  }

  /**
   * Writes the ledger back to the configured path.
   *
   * @param ledger the ledger to save
   */
  public void save(QuarantineLedger ledger) {
    ledger.save(file);
  }

  /**
   * The configured path.
   *
   * @return ledger file
   */
  public Path file() {
    return file;
  }
}
