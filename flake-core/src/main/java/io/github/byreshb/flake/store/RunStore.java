package io.github.byreshb.flake.store;

import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import java.util.Collection;
import java.util.List;

/**
 * Run history. Runs are identified by test, build, attempt and rerun index; recording the same run
 * twice is a no-op, so a report directory can be ingested repeatedly without inflating counts.
 *
 * <p>Every query returns runs in chronological order: by build start time, then attempt, then rerun
 * index.
 */
public interface RunStore extends AutoCloseable {

  /**
   * Stores runs that are not already present.
   *
   * @param runs runs to store
   * @return how many were new
   */
  int record(Collection<TestRun> runs);

  /**
   * All runs of one test.
   *
   * @param testId the test
   * @return runs in chronological order, empty when the test is unknown
   */
  List<TestRun> runsOf(TestId testId);

  /**
   * Every run in the store.
   *
   * @return runs ordered by test, then chronologically
   */
  List<TestRun> allRuns();

  /**
   * Every test that has at least one run.
   *
   * @return test ids in natural order
   */
  List<TestId> testIds();

  /**
   * Number of runs stored.
   *
   * @return the run count
   */
  long runCount();

  /**
   * Number of distinct builds (id and attempt) stored.
   *
   * @return the build count
   */
  int buildCount();

  @Override
  void close();
}
