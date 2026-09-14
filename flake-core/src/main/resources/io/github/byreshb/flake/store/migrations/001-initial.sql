-- Builds: one row per workflow run attempt (or local build).
CREATE TABLE builds (
  id         TEXT    NOT NULL,
  attempt    INTEGER NOT NULL,
  commit_sha TEXT    NOT NULL,
  started_ms INTEGER NOT NULL,
  PRIMARY KEY (id, attempt)
);

-- Test runs: one row per execution of a test in a build (Surefire reruns are separate rows).
CREATE TABLE test_runs (
  id            INTEGER PRIMARY KEY,
  class_name    TEXT    NOT NULL,
  method_name   TEXT    NOT NULL,
  build_id      TEXT    NOT NULL,
  build_attempt INTEGER NOT NULL,
  branch        TEXT    NOT NULL,
  runner        TEXT    NOT NULL,
  rerun         INTEGER NOT NULL,
  duration_ms   INTEGER NOT NULL,
  outcome       TEXT    NOT NULL,
  failure_hash  TEXT,
  UNIQUE (class_name, method_name, build_id, build_attempt, rerun),
  FOREIGN KEY (build_id, build_attempt) REFERENCES builds (id, attempt)
);

CREATE INDEX test_runs_by_test ON test_runs (class_name, method_name);
