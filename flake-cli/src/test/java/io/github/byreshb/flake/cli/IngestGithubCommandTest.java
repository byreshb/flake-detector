package io.github.byreshb.flake.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.byreshb.flake.github.GitHubClient;
import io.github.byreshb.flake.model.TestId;
import io.github.byreshb.flake.store.SqliteRunStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Argument handling is tested here directly (no network needed); the HTTP and artifact-unzipping
 * pipeline itself is tested thoroughly in {@code flake-github}'s own tests. The happy-path test
 * below exercises the full command against a local server to check the wiring end to end.
 */
class IngestGithubCommandTest {

  private static final String REPORT_XML =
      "<testsuite name=\"com.acme.CheckoutTest\">"
          + "<testcase name=\"addsItem\" classname=\"com.acme.CheckoutTest\" time=\"0.1\"/>"
          + "</testsuite>";

  @TempDir Path tmp;
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private CliTestSupport cli(Map<String, String> env, GitHubClient client) {
    return new CliTestSupport(new CommandLine(new IngestGithubCommand(env, client)));
  }

  @Test
  void failsClearlyWithoutAToken() {
    CliTestSupport cli = cli(Map.of(), null);

    int status = cli.run("--repo", "o/r", "--workflow", "ci.yml");

    assertThat(status).isEqualTo(1);
    assertThat(cli.err.toString()).contains("GITHUB_TOKEN is not set");
  }

  @Test
  void rejectsARepoThatIsNotOwnerSlashName() {
    CliTestSupport cli = cli(Map.of("GITHUB_TOKEN", "t"), null);

    int status = cli.run("--repo", "not-a-repo", "--workflow", "ci.yml");

    assertThat(status).isEqualTo(1);
    assertThat(cli.err.toString()).contains("--repo must be OWNER/NAME");
  }

  @Test
  void ingestsFromAWorkflowsArtifactsEndToEnd() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    AtomicInteger runsPageCalls = new AtomicInteger();
    server.createContext(
        "/repos/o/r/actions/workflows/ci.yml/runs",
        exchange -> {
          // Real page past the end answers empty; imitate that so a --runs limit larger than the
          // number of runs that exist does not make the client re-request the same page forever.
          String body =
              runsPageCalls.getAndIncrement() == 0
                  ? "{\"workflow_runs\": [{\"id\": 1, \"head_sha\": \"abc\", \"head_branch\":"
                      + " \"main\", \"run_attempt\": 1, \"created_at\":"
                      + " \"2026-01-01T00:00:00Z\"}]}"
                  : "{\"workflow_runs\": []}";
          respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        });
    server.createContext(
        "/repos/o/r/actions/runs/1/artifacts",
        exchange -> {
          String base = "http://127.0.0.1:" + server.getAddress().getPort();
          String body =
              "{\"artifacts\": [{\"id\": 9, \"name\": \"surefire-reports\","
                  + " \"archive_download_url\": \""
                  + base
                  + "/zip\", \"expired\": false}]}";
          respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        });
    server.createContext(
        "/zip",
        exchange -> respond(exchange, 200, "application/zip", zip("TEST-a.xml", REPORT_XML)));
    server.start();

    GitHubClient client =
        new GitHubClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "t",
            HttpClient.newHttpClient());
    Path db = tmp.resolve("history.db");
    CliTestSupport cli = cli(Map.of("GITHUB_TOKEN", "t"), client);

    int status =
        cli.run("--repo", "o/r", "--workflow", "ci.yml", "--runs", "5", "--db", db.toString());

    assertThat(status).isZero();
    assertThat(cli.out.toString())
        .contains("Ingested 1 run(s) from up to 5 run(s) of ci.yml (o/r), 1 new");
    try (SqliteRunStore store = SqliteRunStore.open(db)) {
      assertThat(store.testIds()).containsExactly(TestId.parse("com.acme.CheckoutTest#addsItem"));
      assertThat(store.runsOf(TestId.parse("com.acme.CheckoutTest#addsItem")).get(0).runner())
          .isEqualTo("github-actions");
    }
  }

  private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static byte[] zip(String entryName, String content) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
