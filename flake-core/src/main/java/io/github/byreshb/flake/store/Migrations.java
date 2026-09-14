package io.github.byreshb.flake.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the numbered SQL migrations bundled as resources ({@code migrations/001-*.sql}, {@code
 * 002-*.sql}, ...) to a SQLite database, tracking the applied version in {@code PRAGMA
 * user_version}.
 */
final class Migrations {

  private static final String PREFIX = "/io/github/byreshb/flake/store/migrations/";
  private static final List<String> NAMES = List.of("001-initial.sql");

  private Migrations() {}

  /**
   * The migration scripts in order.
   *
   * @return resource names, without the directory prefix
   */
  static List<String> names() {
    return NAMES;
  }

  /**
   * Applies every migration newer than the database's current version.
   *
   * @param connection an open connection
   * @return how many migrations were applied
   * @throws SQLException when a script fails
   */
  static int apply(Connection connection) throws SQLException {
    int current = currentVersion(connection);
    int applied = 0;
    for (int version = current + 1; version <= NAMES.size(); version++) {
      String name = NAMES.get(version - 1);
      List<String> statements = statements(read(name));
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        for (String sql : statements) {
          statement.execute(sql);
        }
        statement.execute("PRAGMA user_version = " + version);
        connection.commit();
        applied++;
      } catch (SQLException e) {
        connection.rollback();
        throw new SQLException("migration " + name + " failed: " + e.getMessage(), e);
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    }
    return applied;
  }

  static int currentVersion(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  private static String read(String name) {
    try (InputStream in = Migrations.class.getResourceAsStream(PREFIX + name)) {
      if (in == null) {
        throw new IllegalStateException("missing migration resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read migration resource " + name, e);
    }
  }

  /** Splits a script on semicolons at line ends, dropping comment lines and blank statements. */
  static List<String> statements(String script) {
    StringBuilder current = new StringBuilder();
    List<String> statements = new ArrayList<>();
    for (String line : script.lines().toList()) {
      String trimmed = line.strip();
      if (trimmed.startsWith("--") || trimmed.isEmpty()) {
        continue;
      }
      current.append(line).append('\n');
      if (trimmed.endsWith(";")) {
        statements.add(current.toString().strip());
        current.setLength(0);
      }
    }
    if (!current.toString().isBlank()) {
      statements.add(current.toString().strip());
    }
    return statements;
  }
}
