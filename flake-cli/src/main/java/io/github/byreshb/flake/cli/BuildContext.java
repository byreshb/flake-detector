package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.model.BuildRun;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Works out which build a local report directory belongs to. Every value comes, in order of
 * preference, from an explicit command line option, from the GitHub Actions environment ({@code
 * GITHUB_SHA}, {@code GITHUB_REF_NAME}, {@code GITHUB_RUN_ID}, {@code GITHUB_RUN_ATTEMPT}, {@code
 * RUNNER_NAME}), from git in the working directory, or from a default.
 */
public final class BuildContext {

  private final Map<String, String> env;
  private final Path workingDirectory;
  private final Clock clock;

  /**
   * Creates a context.
   *
   * @param env environment variables
   * @param workingDirectory where to run git
   * @param clock source of "now" for the build timestamp and the fallback build id
   */
  public BuildContext(Map<String, String> env, Path workingDirectory, Clock clock) {
    this.env = Objects.requireNonNull(env, "env");
    this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * A context using the real environment, current directory and clock.
   *
   * @return the context
   */
  public static BuildContext fromEnvironment() {
    return new BuildContext(System.getenv(), Path.of("").toAbsolutePath(), Clock.systemUTC());
  }

  /**
   * Resolves the build.
   *
   * @param buildId explicit id or null
   * @param commit explicit commit or null
   * @param attempt explicit attempt or null
   * @return the build, with a timestamp of now
   */
  public BuildRun build(String buildId, String commit, Integer attempt) {
    Instant now = clock.instant();
    String id = first(buildId, env.get("GITHUB_RUN_ID"), "local-" + now.toEpochMilli());
    String sha = first(commit, env.get("GITHUB_SHA"), git("rev-parse", "HEAD").orElse("unknown"));
    int number = attempt != null ? attempt : parseAttempt(env.get("GITHUB_RUN_ATTEMPT"));
    return new BuildRun(id, sha, number, now);
  }

  /**
   * Resolves the branch.
   *
   * @param branch explicit branch or null
   * @return the branch, empty when unknown
   */
  public String branch(String branch) {
    return first(
        branch,
        env.get("GITHUB_HEAD_REF"),
        env.get("GITHUB_REF_NAME"),
        git("rev-parse", "--abbrev-ref", "HEAD").filter(b -> !b.equals("HEAD")).orElse(""));
  }

  /**
   * Resolves the runner label.
   *
   * @param runner explicit label or null
   * @return the label, empty when unknown
   */
  public String runner(String runner) {
    return first(runner, env.get("RUNNER_NAME"), "");
  }

  private static int parseAttempt(String value) {
    if (value == null || value.isBlank()) {
      return 1;
    }
    try {
      return Math.max(1, Integer.parseInt(value.trim()));
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private static String first(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate.trim();
      }
    }
    return "";
  }

  private Optional<String> git(String... args) {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(workingDirectory.toFile())
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        return Optional.empty();
      }
      String trimmed = output.strip();
      return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
