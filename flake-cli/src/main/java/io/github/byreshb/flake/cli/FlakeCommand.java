package io.github.byreshb.flake.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the {@code flake} command. */
@Command(
    name = "flake",
    mixinStandardHelpOptions = true,
    versionProvider = FlakeCommand.Version.class,
    description = "Statistical flaky-test detection and quarantine for JUnit and Maven projects.",
    subcommands = {
      IngestCommand.class,
      ScoreCommand.class,
      QuarantineCommand.class,
      GateCommand.class
    })
public final class FlakeCommand {

  /**
   * Runs the command with the given arguments and exits with its status.
   *
   * @param args command line arguments
   */
  public static void main(String[] args) {
    System.exit(commandLine().execute(args));
  }

  /**
   * Builds the picocli command line for the tool, ready to execute.
   *
   * @return a new command line
   */
  public static CommandLine commandLine() {
    return new CommandLine(new FlakeCommand()).setCaseInsensitiveEnumValuesAllowed(true);
  }

  /** Reports the version from the jar manifest. */
  public static final class Version implements CommandLine.IVersionProvider {

    @Override
    public String[] getVersion() {
      String version = FlakeCommand.class.getPackage().getImplementationVersion();
      return new String[] {"flake " + (version == null ? "development" : version)};
    }
  }
}
