package io.github.byreshb.flake.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.byreshb.flake.model.Outcome;
import io.github.byreshb.flake.model.TestRun;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubArtifactsSourceTest {

  private static final String CHECKOUT_XML =
      "<testsuite name=\"com.acme.CheckoutTest\"><testcase name=\"addsItem\""
          + " classname=\"com.acme.CheckoutTest\" time=\"0.1\"/>"
          + "<testcase name=\"appliesCoupon\" classname=\"com.acme.CheckoutTest\" time=\"0.2\">"
          + "<failure message=\"boom\" type=\"AssertionError\">boom</failure>"
          + "</testcase></testsuite>";

  private final TestHttpServer server = TestHttpServer.start();

  @AfterEach
  void stopServer() {
    server.close();
  }

  private GitHubClient client(String token) {
    return new GitHubClient(server.baseUri(), token, HttpClient.newHttpClient());
  }

  /**
   * Serves the given runs on the first page and an empty array on every page after, the way the
   * real API answers a page past the end, so a source asking for more runs than exist stops instead
   * of re-requesting the same page forever.
   */
  private void serveRuns(String... runsJson) {
    AtomicInteger calls = new AtomicInteger();
    String body = "{\"workflow_runs\": [" + String.join(",", runsJson) + "]}";
    server.handle(
        "/repos/o/r/actions/workflows/ci.yml/runs",
        exchange -> {
          String response = calls.getAndIncrement() == 0 ? body : "{\"workflow_runs\": []}";
          TestHttpServer.respond(
              exchange,
              200,
              "application/json",
              response.getBytes(StandardCharsets.UTF_8),
              Map.of());
        });
  }

  private String run(long id, String sha, String branch, int attempt) {
    String branchField = branch == null ? "null" : "\"" + branch + "\"";
    return String.format(
        "{\"id\": %d, \"head_sha\": \"%s\", \"head_branch\": %s, \"run_attempt\": %d,"
            + " \"created_at\": \"2026-01-01T00:00:00Z\", \"html_url\": \"https://x/%d\"}",
        id, sha, branchField, attempt, id);
  }

  private void serveArtifacts(long runId, String... artifactsJson) {
    server.json(
        "/repos/o/r/actions/runs/" + runId + "/artifacts",
        200,
        "{\"artifacts\": [" + String.join(",", artifactsJson) + "]}");
  }

  private String artifact(long id, String name, String downloadUrl, boolean expired) {
    return String.format(
        "{\"id\": %d, \"name\": \"%s\", \"archive_download_url\": \"%s\", \"expired\": %b}",
        id, name, downloadUrl, expired);
  }

  private void serveZip(String path, Map<String, byte[]> entries) {
    server.handle(
        path,
        exchange -> {
          byte[] zip = zip(entries);
          TestHttpServer.respond(exchange, 200, "application/zip", zip, Map.of());
        });
  }

  private static byte[] zip(Map<String, byte[]> entries) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
          zip.putNextEntry(new ZipEntry(entry.getKey()));
          zip.write(entry.getValue());
          zip.closeEntry();
        }
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void readsRunsFromAMatchingArtifactAndSkipsNonXmlEntries() {
    serveRuns(run(1, "abc", "main", 1));
    serveArtifacts(1, artifact(10, "surefire-reports", server.baseUri() + "/zip/10", false));
    serveZip(
        "/zip/10",
        Map.of(
            "TEST-com.acme.CheckoutTest.xml",
            CHECKOUT_XML.getBytes(StandardCharsets.UTF_8),
            "console.log",
            "not xml".getBytes(StandardCharsets.UTF_8)));
    GitHubArtifactsSource source = new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 10);

    List<TestRun> runs = source.read();

    assertThat(runs).hasSize(2);
    assertThat(runs)
        .extracting(r -> r.testId().toString())
        .containsExactlyInAnyOrder(
            "com.acme.CheckoutTest#addsItem", "com.acme.CheckoutTest#appliesCoupon");
    assertThat(runs).extracting(TestRun::commit).containsOnly("abc");
    assertThat(runs).extracting(TestRun::branch).containsOnly("main");
    assertThat(runs).extracting(r -> r.build().attempt()).containsOnly(1);
    assertThat(runs).extracting(TestRun::runner).containsOnly(GitHubArtifactsSource.DEFAULT_RUNNER);
    assertThat(runs)
        .extracting(TestRun::outcome)
        .containsExactlyInAnyOrder(Outcome.PASS, Outcome.FAIL);
  }

  @Test
  void skipsExpiredAndNonMatchingArtifacts() {
    serveRuns(run(1, "abc", "main", 1));
    serveArtifacts(
        1,
        artifact(10, "surefire-reports", server.baseUri() + "/zip/expired", true),
        artifact(11, "screenshots", server.baseUri() + "/zip/other", false),
        artifact(12, "failsafe-reports", server.baseUri() + "/zip/12", false));
    serveZip(
        "/zip/12",
        Map.of("TEST-com.acme.CheckoutTest.xml", CHECKOUT_XML.getBytes(StandardCharsets.UTF_8)));
    GitHubArtifactsSource source = new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 10);

    List<Artifact> matching = source.matchingArtifacts(1);

    assertThat(matching).extracting(Artifact::id).containsExactly(12L);
    assertThat(source.read()).hasSize(2);
  }

  @Test
  void aMissingBranchBecomesAnEmptyString() {
    serveRuns(run(1, "abc", null, 1));
    serveArtifacts(1);

    List<WorkflowRun> runs = new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 10).runs();

    assertThat(runs).extracting(WorkflowRun::headBranch).containsExactly((String) null);
    assertThat(new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 10).read()).isEmpty();
  }

  @Test
  void runAttemptDefaultsToOneWhenAbsentFromTheResponse() {
    server.json(
        "/repos/o/r/actions/workflows/ci.yml/runs",
        200,
        "{\"workflow_runs\": [{\"id\": 1, \"head_sha\": \"abc\", \"head_branch\": \"main\","
            + " \"created_at\": \"2026-01-01T00:00:00Z\"}]}");

    List<WorkflowRun> runs = new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 1).runs();

    assertThat(runs).extracting(WorkflowRun::runAttempt).containsExactly(1);
  }

  @Test
  void stopsAtMaxRunsEvenWhenAPageReturnsMore() {
    server.json(
        "/repos/o/r/actions/workflows/ci.yml/runs",
        200,
        "{\"workflow_runs\": [" + run(1, "a", "main", 1) + "," + run(2, "b", "main", 1) + "]}");

    List<WorkflowRun> runs = new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 1).runs();

    assertThat(runs).hasSize(1);
    assertThat(runs.get(0).id()).isEqualTo(1L);
  }

  @Test
  void paginatesWhenMoreRunsAreNeededThanOnePageHolds() {
    AtomicInteger page = new AtomicInteger();
    server.handle(
        "/repos/o/r/actions/workflows/ci.yml/runs",
        exchange -> {
          String body =
              page.incrementAndGet() == 1
                  ? "{\"workflow_runs\": [" + run(1, "a", "main", 1) + "]}"
                  : "{\"workflow_runs\": [" + run(2, "b", "main", 1) + "]}";
          TestHttpServer.respond(
              exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8), Map.of());
        });

    List<WorkflowRun> runs = new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 2).runs();

    assertThat(runs).extracting(WorkflowRun::id).containsExactly(1L, 2L);
    assertThat(page).hasValueGreaterThanOrEqualTo(2);
  }

  @Test
  void customGlobAndRunnerLabelAreHonoured() {
    serveRuns(run(1, "abc", "main", 1));
    serveArtifacts(1, artifact(10, "my-reports", server.baseUri() + "/zip/10", false));
    serveZip(
        "/zip/10",
        Map.of("TEST-com.acme.CheckoutTest.xml", CHECKOUT_XML.getBytes(StandardCharsets.UTF_8)));

    GitHubArtifactsSource source =
        new GitHubArtifactsSource(client("t"), "o", "r", "ci.yml", 10, "my-*", "custom-runner");

    List<TestRun> runs = source.read();

    assertThat(runs).extracting(TestRun::runner).containsOnly("custom-runner");
  }
}
