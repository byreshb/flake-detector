package io.github.byreshb.flake.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.model.BuildRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildContextTest {

  private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @TempDir Path tmp;

  @Test
  void explicitValuesWinOverEverything() {
    BuildContext context =
        new BuildContext(Map.of("GITHUB_SHA", "envsha", "GITHUB_RUN_ID", "7"), tmp, CLOCK);

    BuildRun build = context.build("42", "abc", 3);

    assertThat(build).isEqualTo(new BuildRun("42", "abc", 3, NOW));
    assertThat(context.branch("feature")).isEqualTo("feature");
    assertThat(context.runner("mac-1")).isEqualTo("mac-1");
  }

  @Test
  void readsTheGitHubActionsEnvironment() {
    Map<String, String> env =
        Map.of(
            "GITHUB_SHA", "envsha",
            "GITHUB_RUN_ID", "7",
            "GITHUB_RUN_ATTEMPT", "2",
            "GITHUB_REF_NAME", "main",
            "RUNNER_NAME", "GitHub Actions 3");
    BuildContext context = new BuildContext(env, tmp, CLOCK);

    assertThat(context.build(null, null, null)).isEqualTo(new BuildRun("7", "envsha", 2, NOW));
    assertThat(context.branch(null)).isEqualTo("main");
    assertThat(context.runner(null)).isEqualTo("GitHub Actions 3");
  }

  @Test
  void prefersPullRequestHeadRefOverRefName() {
    BuildContext context =
        new BuildContext(
            Map.of("GITHUB_HEAD_REF", "pr-branch", "GITHUB_REF_NAME", "12/merge"), tmp, CLOCK);

    assertThat(context.branch(null)).isEqualTo("pr-branch");
  }

  @Test
  void fallsBackToGitThenDefaultsOutsideActions() throws Exception {
    Path repo = Files.createDirectories(tmp.resolve("repo"));
    git(repo, "init", "-q", "-b", "trunk");
    git(
        repo,
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@t",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "x");
    String head =
        new String(
                new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(repo.toFile())
                    .start()
                    .getInputStream()
                    .readAllBytes())
            .strip();
    BuildContext context = new BuildContext(Map.of("GITHUB_RUN_ATTEMPT", "junk"), repo, CLOCK);

    BuildRun build = context.build(null, null, null);

    assertThat(build.commit()).isEqualTo(head);
    assertThat(build.id()).isEqualTo("local-" + NOW.toEpochMilli());
    assertThat(build.attempt()).isEqualTo(1);
    assertThat(context.branch(null)).isEqualTo("trunk");
    assertThat(context.runner(null)).isEmpty();
  }

  @Test
  void usesUnknownWhenThereIsNoGitRepository() {
    BuildContext context = new BuildContext(Map.of(), tmp, CLOCK);

    assertThat(context.build(null, null, null).commit()).isEqualTo("unknown");
    assertThat(context.branch(null)).isEmpty();
  }

  private static void git(Path dir, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    assertThat(p.waitFor()).as(new String(p.getInputStream().readAllBytes())).isZero();
  }
}
