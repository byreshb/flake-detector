package io.github.byreshb.flake.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestRun;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDirectorySourceTest {

  private static final Path FIXTURES = Path.of("src/test/resources/surefire");
  private static final BuildRun BUILD = new BuildRun("9", "abc", 1, Instant.EPOCH);

  @TempDir Path tmp;

  @Test
  void readsOnlySurefireNamedFilesByDefault() {
    LocalDirectorySource source = new LocalDirectorySource(FIXTURES, BUILD, "main", "mac");

    assertThat(source.files())
        .extracting(p -> p.getFileName().toString())
        .containsExactly("TEST-com.acme.CheckoutTest.xml", "TEST-com.acme.SearchTest.xml");

    List<TestRun> runs = source.read();
    // Checkout: 1 + 3 + 3 + 2 + 1 executions, Search: 3.
    assertThat(runs).hasSize(13);
    assertThat(runs).extracting(TestRun::build).containsOnly(BUILD);
    assertThat(runs).extracting(TestRun::runner).containsOnly("mac");
    assertThat(runs).filteredOn(TestRun::failed).hasSize(7);
    assertThat(runs).filteredOn(r -> r.outcome() == Outcome.SKIPPED).hasSize(1);
  }

  @Test
  void walksSubdirectoriesWithACustomGlob() throws Exception {
    Path a = Files.createDirectories(tmp.resolve("module-a/target/surefire-reports"));
    Path b = Files.createDirectories(tmp.resolve("module-b/target/failsafe-reports"));
    Files.copy(
        FIXTURES.resolve("TEST-com.acme.SearchTest.xml"),
        a.resolve("TEST-com.acme.SearchTest.xml"));
    Files.copy(FIXTURES.resolve("testsuites-nested.xml"), b.resolve("nested.xml"));
    Files.writeString(tmp.resolve("notes.txt"), "ignored");

    LocalDirectorySource source = new LocalDirectorySource(tmp, "*.xml", BUILD, "", "");

    assertThat(source.files()).hasSize(2);
    assertThat(source.read()).hasSize(5);
  }

  @Test
  void rejectsMissingDirectory() {
    LocalDirectorySource source = new LocalDirectorySource(tmp.resolve("nope"), BUILD, "", "");

    assertThatThrownBy(source::files)
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("nope");
  }
}
