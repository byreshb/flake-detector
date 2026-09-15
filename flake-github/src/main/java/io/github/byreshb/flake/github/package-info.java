/**
 * GitHub integration: a thin REST client over {@code java.net.http}, and {@code
 * GitHubArtifactsSource}, which lists a workflow's runs, downloads their Surefire/Failsafe report
 * artifacts, and feeds them to {@link io.github.byreshb.flake.ingest.JUnitXmlParser}.
 */
package io.github.byreshb.flake.github;
