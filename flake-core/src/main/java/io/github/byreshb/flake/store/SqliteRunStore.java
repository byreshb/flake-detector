package io.github.byreshb.flake.store;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * {@link RunStore} backed by a single SQLite file, by convention {@code .flake/history.db}. The
 * schema is created and upgraded on open by the numbered migration scripts in the {@code
 * migrations} resource directory.
 */
public final class SqliteRunStore implements RunStore {

  /** Default location of the history database, relative to the project root. */
  public static final Path DEFAULT_PATH = Path.of(".flake", "history.db");

  private static final String SELECT_RUNS =
      "SELECT r.class_name, r.method_name, r.build_id, r.build_attempt, b.commit_sha, "
          + "b.started_ms, r.branch, r.runner, r.rerun, r.duration_ms, r.outcome, r.failure_hash "
          + "FROM test_runs r JOIN builds b ON b.id = r.build_id AND b.attempt = r.build_attempt ";
  private static final String CHRONOLOGICAL = "ORDER BY b.started_ms, r.build_attempt, r.rerun";

  private final Connection connection;
  private final Path path;

  private SqliteRunStore(Connection connection, Path path) {
    this.connection = connection;
    this.path = path;
  }

  /**
   * Opens (creating and migrating as needed) the database at the given path.
   *
   * @param path database file; parent directories are created
   * @return the open store
   * @throws StoreException when the file cannot be opened or migrated
   */
  public static SqliteRunStore open(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys = ON");
      }
      Migrations.apply(connection);
      return new SqliteRunStore(connection, path);
    } catch (IOException | SQLException e) {
      throw new StoreException("cannot open run store " + path + ": " + e.getMessage(), e);
    }
  }

  /**
   * Opens an in-memory store that disappears on close; for tests and dry runs.
   *
   * @return the open store
   */
  public static SqliteRunStore inMemory() {
    try {
      Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
      Migrations.apply(connection);
      return new SqliteRunStore(connection, null);
    } catch (SQLException e) {
      throw new StoreException("cannot open in-memory run store", e);
    }
  }

  /**
   * Where this store lives.
   *
   * @return the file, or null for an in-memory store
   */
  public Path path() {
    return path;
  }

  @Override
  public int record(Collection<TestRun> runs) {
    Objects.requireNonNull(runs, "runs");
    String insertBuild =
        "INSERT OR IGNORE INTO builds (id, attempt, commit_sha, started_ms) VALUES (?, ?, ?, ?)";
    String insertRun =
        "INSERT OR IGNORE INTO test_runs (class_name, method_name, build_id, build_attempt, "
            + "branch, runner, rerun, duration_ms, outcome, failure_hash) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try {
      connection.setAutoCommit(false);
      int inserted = 0;
      try (PreparedStatement builds = connection.prepareStatement(insertBuild);
          PreparedStatement inserts = connection.prepareStatement(insertRun)) {
        for (TestRun run : runs) {
          BuildRun build = run.build();
          builds.setString(1, build.id());
          builds.setInt(2, build.attempt());
          builds.setString(3, build.commit());
          builds.setLong(4, build.timestamp().toEpochMilli());
          builds.executeUpdate();

          inserts.setString(1, run.testId().className());
          inserts.setString(2, run.testId().methodName());
          inserts.setString(3, build.id());
          inserts.setInt(4, build.attempt());
          inserts.setString(5, run.branch());
          inserts.setString(6, run.runner());
          inserts.setInt(7, run.rerun());
          inserts.setLong(8, run.duration().toMillis());
          inserts.setString(9, run.outcome().name());
          inserts.setString(10, run.failureMessageHash());
          inserted += inserts.executeUpdate();
        }
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
      return inserted;
    } catch (SQLException e) {
      throw new StoreException("cannot record runs: " + e.getMessage(), e);
    }
  }

  @Override
  public List<TestRun> runsOf(TestId testId) {
    Objects.requireNonNull(testId, "testId");
    String sql = SELECT_RUNS + "WHERE r.class_name = ? AND r.method_name = ? " + CHRONOLOGICAL;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, testId.className());
      statement.setString(2, testId.methodName());
      try (ResultSet rs = statement.executeQuery()) {
        return read(rs);
      }
    } catch (SQLException e) {
      throw new StoreException("cannot read runs of " + testId, e);
    }
  }

  @Override
  public List<TestRun> allRuns() {
    String sql =
        SELECT_RUNS
            + "ORDER BY r.class_name, r.method_name, b.started_ms, r.build_attempt, r.rerun";
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      return read(rs);
    } catch (SQLException e) {
      throw new StoreException("cannot read runs", e);
    }
  }

  @Override
  public List<TestId> testIds() {
    String sql =
        "SELECT DISTINCT class_name, method_name FROM test_runs ORDER BY class_name, method_name";
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      List<TestId> ids = new ArrayList<>();
      while (rs.next()) {
        ids.add(new TestId(rs.getString(1), rs.getString(2)));
      }
      return ids;
    } catch (SQLException e) {
      throw new StoreException("cannot list tests", e);
    }
  }

  @Override
  public long runCount() {
    return count("SELECT COUNT(*) FROM test_runs");
  }

  @Override
  public int buildCount() {
    return (int) count("SELECT COUNT(*) FROM builds");
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new StoreException("cannot close run store", e);
    }
  }

  private long count(String sql) {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : 0;
    } catch (SQLException e) {
      throw new StoreException("cannot count", e);
    }
  }

  private static List<TestRun> read(ResultSet rs) throws SQLException {
    List<TestRun> runs = new ArrayList<>();
    while (rs.next()) {
      BuildRun build =
          new BuildRun(
              rs.getString("build_id"),
              rs.getString("commit_sha"),
              rs.getInt("build_attempt"),
              Instant.ofEpochMilli(rs.getLong("started_ms")));
      runs.add(
          new TestRun(
              new TestId(rs.getString("class_name"), rs.getString("method_name")),
              build,
              rs.getString("branch"),
              rs.getString("runner"),
              rs.getInt("rerun"),
              Duration.ofMillis(rs.getLong("duration_ms")),
              Outcome.valueOf(rs.getString("outcome")),
              rs.getString("failure_hash")));
    }
    return runs;
  }
}
