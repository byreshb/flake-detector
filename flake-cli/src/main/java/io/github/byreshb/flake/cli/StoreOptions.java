package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.store.SqliteRunStore;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/** The {@code --db} option shared by every subcommand that touches the run history. */
public final class StoreOptions {

  @Option(
      names = {"--db"},
      paramLabel = "FILE",
      description = "Run history database (default: ${DEFAULT-VALUE}).",
      defaultValue = ".flake/history.db")
  private Path database;

  /**
   * Opens the store at the configured path.
   *
   * @return the open store; the caller closes it
   */
  public SqliteRunStore open() {
    return SqliteRunStore.open(database);
  }

  /**
   * The configured path.
   *
   * @return database file
   */
  public Path database() {
    return database;
  }
}
