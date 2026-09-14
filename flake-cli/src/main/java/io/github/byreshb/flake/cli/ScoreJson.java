package io.github.byreshb.flake.cli;

import io.github.byreshb.flake.score.FlakeScore;
import java.util.List;
import java.util.Locale;

/** Renders scores as JSON without a JSON library; the shape is documented in the README. */
final class ScoreJson {

  private ScoreJson() {}

  static String render(List<FlakeScore> scores) {
    StringBuilder json = new StringBuilder("[\n");
    for (int i = 0; i < scores.size(); i++) {
      FlakeScore s = scores.get(i);
      json.append("  {")
          .append("\"test\": ")
          .append(quote(s.testId().toString()))
          .append(", \"score\": ")
          .append(number(s.score()))
          .append(", \"runs\": ")
          .append(s.runs())
          .append(", \"failures\": ")
          .append(s.failures())
          .append(", \"flipPairs\": ")
          .append(s.flipPairs())
          .append(", \"flips\": ")
          .append(s.flips())
          .append(", \"flipRate\": ")
          .append(number(s.flipRate()))
          .append(", \"flipRateLower\": ")
          .append(number(s.flipRateInterval().lower()))
          .append(", \"flipRateUpper\": ")
          .append(number(s.flipRateInterval().upper()))
          .append(", \"recoveredFailures\": ")
          .append(s.recoveredFailures())
          .append(", \"rerunRecoveryRate\": ")
          .append(number(s.rerunRecoveryRate()))
          .append(", \"rerunRecoveryLower\": ")
          .append(number(s.rerunRecoveryInterval().lower()))
          .append(", \"distinctMessages\": ")
          .append(s.distinctMessages())
          .append(", \"entropy\": ")
          .append(number(s.entropy()))
          .append(", \"entropyComponent\": ")
          .append(number(s.entropyComponent()))
          .append(", \"runnerCorrelation\": ")
          .append(number(s.runnerCorrelation()))
          .append(", \"hourCorrelation\": ")
          .append(number(s.hourCorrelation()))
          .append('}')
          .append(i + 1 < scores.size() ? ",\n" : "\n");
    }
    return json.append("]\n").toString();
  }

  static String number(double value) {
    return String.format(Locale.ROOT, "%.6f", value);
  }

  static String quote(String text) {
    StringBuilder quoted = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> quoted.append("\\\"");
        case '\\' -> quoted.append("\\\\");
        case '\n' -> quoted.append("\\n");
        case '\r' -> quoted.append("\\r");
        case '\t' -> quoted.append("\\t");
        default -> {
          if (c < 0x20) {
            quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            quoted.append(c);
          }
        }
      }
    }
    return quoted.append('"').toString();
  }
}
