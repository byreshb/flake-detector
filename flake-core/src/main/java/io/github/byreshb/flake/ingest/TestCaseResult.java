package io.github.byreshb.flake.ingest;

import io.github.byreshb.flake.model.BuildRun;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.model.TestRun;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@code testcase} element of a report: the test, its total time and every execution in order.
 * Surefire reruns appear as several executions; the last one is the final outcome.
 *
 * @param testId the test
 * @param duration total time reported for the test case
 * @param executions the executions in the order they happened, never empty
 */
public record TestCaseResult(TestId testId, Duration duration, List<Execution> executions) {

  /**
   * Validates the components and takes a defensive copy of the executions.
   *
   * @param testId the test
   * @param duration total time
   * @param executions executions in order
   */
  public TestCaseResult {
    Objects.requireNonNull(testId, "testId");
    Objects.requireNonNull(duration, "duration");
    executions = List.copyOf(executions);
    if (executions.isEmpty()) {
      throw new IllegalArgumentException("a test case needs at least one execution");
    }
  }

  /**
   * The outcome of the last execution.
   *
   * @return the final execution
   */
  public Execution last() {
    return executions.get(executions.size() - 1);
  }

  /**
   * Converts the executions into test runs tied to a build.
   *
   * @param build the build the report came from
   * @param branch branch name, empty when unknown
   * @param runner runner label, empty when unknown
   * @return one run per execution, rerun index in execution order
   */
  public List<TestRun> toRuns(BuildRun build, String branch, String runner) {
    List<TestRun> runs = new ArrayList<>(executions.size());
    for (int i = 0; i < executions.size(); i++) {
      Execution execution = executions.get(i);
      runs.add(
          new TestRun(
              testId,
              build,
              branch,
              runner,
              i,
              duration,
              execution.outcome(),
              execution.failureMessageHash()));
    }
    return runs;
  }
}
