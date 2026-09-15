/**
 * Rendering a scored run history as a report: a ranked table with a trend sparkline per test, and
 * explanations of the top suspects. Markdown and single-file HTML output share the same input,
 * {@link io.github.byreshb.flake.report.ReportEntry}, so both stay in sync with the scorer.
 */
package io.github.byreshb.flake.report;
