package io.github.byreshb.flake.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import picocli.CommandLine;

/** Runs the {@code flake} command in-process and captures its output. */
final class CliTestSupport {

  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();
  private final CommandLine commandLine;

  CliTestSupport() {
    this(FlakeCommand.commandLine());
  }

  CliTestSupport(CommandLine commandLine) {
    this.commandLine = commandLine;
    commandLine.setOut(new PrintWriter(out, true));
    commandLine.setErr(new PrintWriter(err, true));
  }

  int run(String... args) {
    return commandLine.execute(args);
  }
}
