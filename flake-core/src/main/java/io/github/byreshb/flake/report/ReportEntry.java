package io.github.byreshb.flake.report;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.score.FlakeScore;
import java.util.List;
import java.util.Objects;

/**
 * A scored test plus the recent history a report draws a trend from.
 *
 * @param score the flakiness score
 * @param trend outcomes in chronological order (oldest first, skipped runs excluded), capped to the
 *     most recent runs so the sparkline stays a fixed size
 */
public record ReportEntry(FlakeScore score, List<Outcome> trend) {

  /**
   * Validates the components and takes a defensive copy of the trend.
   *
   * @param score the score
   * @param trend the trend
   */
  public ReportEntry {
    Objects.requireNonNull(score, "score");
    trend = List.copyOf(trend);
  }
}
