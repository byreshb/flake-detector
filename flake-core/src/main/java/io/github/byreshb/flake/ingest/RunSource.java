package io.github.byreshb.flake.ingest;

import io.github.byreshb.flake.model.TestRun;
import java.util.List;

/** Somewhere test runs come from: a directory of reports, a GitHub Actions workflow history. */
public interface RunSource {

  /**
   * Reads every run the source can provide.
   *
   * @return the runs, in no particular order
   */
  List<TestRun> read();
}
