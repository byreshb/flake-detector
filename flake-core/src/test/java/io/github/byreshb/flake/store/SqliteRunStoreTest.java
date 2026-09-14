package io.github.byreshb.flake.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRunStoreTest {

  private static final TestId COUPON = TestId.parse("com.acme.CheckoutTest#appliesCoupon");
  private static final TestId ITEM = TestId.parse("com.acme.CheckoutTest#addsItem");
  private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

  @TempDir Path tmp;

  private static TestRun run(TestId id, BuildRun build, int rerun, Outcome outcome) {
    String hash = outcome.isFailure() ? "abcdef0123456789" : null;
    return new TestRun(id, build, "main", "ubuntu", rerun, Duration.ofMillis(10), outcome, hash);
  }

  @Test
  void createsTheFileAndParentDirectoriesAndMigrates() throws SQLException {
    Path db = tmp.resolve("nested/.flake/history.db");

    try (SqliteRunStore store = SqliteRunStore.open(db)) {
      assertThat(store.path()).isEqualTo(db);
      assertThat(store.runCount()).isZero();
      assertThat(store.buildCount()).isZero();
    }

    assertThat(Files.exists(db)).isTrue();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
      assertThat(Migrations.currentVersion(c)).isEqualTo(Migrations.names().size());
      assertThat(Migrations.apply(c)).isZero();
    }
  }

  @Test
  void recordsRunsOnceAndReadsThemBackChronologically() {
    BuildRun first = new BuildRun("1", "c1", 1, T0);
    BuildRun retry = new BuildRun("1", "c1", 2, T0.plusSeconds(600));
    BuildRun later = new BuildRun("2", "c2", 1, T0.plusSeconds(60));
    List<TestRun> runs =
        List.of(
            run(COUPON, later, 0, Outcome.PASS),
            run(COUPON, first, 1, Outcome.PASS),
            run(COUPON, first, 0, Outcome.FAIL),
            run(COUPON, retry, 0, Outcome.ERROR),
            run(ITEM, first, 0, Outcome.SKIPPED));

    try (SqliteRunStore store = SqliteRunStore.inMemory()) {
      assertThat(store.record(runs)).isEqualTo(5);
      assertThat(store.record(runs)).isZero();
      assertThat(store.runCount()).isEqualTo(5);
      assertThat(store.buildCount()).isEqualTo(3);
      assertThat(store.testIds()).containsExactly(ITEM, COUPON);

      List<TestRun> coupon = store.runsOf(COUPON);
      assertThat(coupon)
          .extracting(r -> r.build().id() + "/" + r.build().attempt() + "/" + r.rerun())
          .containsExactly("1/1/0", "1/1/1", "2/1/0", "1/2/0");
      assertThat(coupon)
          .extracting(TestRun::outcome)
          .containsExactly(Outcome.FAIL, Outcome.PASS, Outcome.PASS, Outcome.ERROR);
      assertThat(coupon.get(0)).isEqualTo(run(COUPON, first, 0, Outcome.FAIL));
      assertThat(coupon.get(3).failureMessageHash()).isEqualTo("abcdef0123456789");
      assertThat(coupon.get(1).failureMessageHash()).isNull();

      assertThat(store.runsOf(TestId.parse("x.Y#z"))).isEmpty();
      assertThat(store.allRuns()).hasSize(5);
      assertThat(store.allRuns().get(0).testId()).isEqualTo(ITEM);
    }
  }

  @Test
  void survivesReopening() {
    Path db = tmp.resolve("history.db");
    BuildRun build = new BuildRun("1", "c1", 1, T0);
    try (SqliteRunStore store = SqliteRunStore.open(db)) {
      store.record(List.of(run(COUPON, build, 0, Outcome.PASS)));
    }
    try (SqliteRunStore store = SqliteRunStore.open(db)) {
      assertThat(store.runCount()).isEqualTo(1);
      assertThat(store.record(List.of(run(COUPON, build, 0, Outcome.PASS)))).isZero();
    }
  }

  @Test
  void failsClearlyWhenTheFileIsNotADatabase() throws Exception {
    Path db = tmp.resolve("garbage.db");
    Files.writeString(db, "this is not sqlite");

    assertThatThrownBy(() -> SqliteRunStore.open(db))
        .isInstanceOf(StoreException.class)
        .hasMessageContaining("garbage.db");
  }

  @Test
  void queriesFailAfterClose() {
    SqliteRunStore store = SqliteRunStore.inMemory();
    store.close();

    assertThatThrownBy(store::runCount).isInstanceOf(StoreException.class);
    assertThatThrownBy(store::testIds).isInstanceOf(StoreException.class);
    assertThatThrownBy(store::allRuns).isInstanceOf(StoreException.class);
    assertThatThrownBy(() -> store.runsOf(COUPON)).isInstanceOf(StoreException.class);
    assertThatThrownBy(() -> store.record(List.of())).isInstanceOf(StoreException.class);
  }

  @Test
  void splitsScriptsIntoStatements() {
    List<String> statements =
        Migrations.statements("-- comment\nCREATE TABLE a (\n  x INT\n);\n\nPRAGMA y = 1");

    assertThat(statements).containsExactly("CREATE TABLE a (\n  x INT\n);", "PRAGMA y = 1");
  }
}
