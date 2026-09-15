package io.github.byreshb.flake.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubClientTest {

  private final TestHttpServer server = TestHttpServer.start();

  @AfterEach
  void stopServer() {
    server.close();
  }

  private GitHubClient client(String token) {
    return new GitHubClient(server.baseUri(), token, HttpClient.newHttpClient());
  }

  @Test
  void listsWorkflowRunsAndSendsExpectedHeaders() {
    AtomicBoolean sawHeaders = new AtomicBoolean();
    server.handle(
        "/repos/o/r/actions/workflows/ci.yml/runs",
        exchange -> {
          var headers = exchange.getRequestHeaders();
          sawHeaders.set(
              "flake-detector".equals(headers.getFirst("User-Agent"))
                  && "application/vnd.github+json".equals(headers.getFirst("Accept"))
                  && "2022-11-28".equals(headers.getFirst("X-GitHub-Api-Version"))
                  && "Bearer secret".equals(headers.getFirst("Authorization")));
          String body =
              "{\"workflow_runs\": [{\"id\": 1, \"head_sha\": \"abc\", \"head_branch\": \"main\","
                  + " \"run_attempt\": 2, \"created_at\": \"2026-01-01T00:00:00Z\", \"html_url\":"
                  + " \"https://x\"}]}";
          TestHttpServer.respond(
              exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8), Map.of());
        });

    List<Object> runs = client("secret").listWorkflowRunsPage("o", "r", "ci.yml", 1, 100);

    assertThat(sawHeaders).isTrue();
    assertThat(runs).hasSize(1);
  }

  @Test
  void listsArtifactsWithoutATokenAndWithoutAnAuthorizationHeader() {
    AtomicBoolean sawAuth = new AtomicBoolean(true);
    server.handle(
        "/repos/o/r/actions/runs/9/artifacts",
        exchange -> {
          sawAuth.set(exchange.getRequestHeaders().containsKey("Authorization"));
          String body =
              "{\"artifacts\": [{\"id\": 5, \"name\": \"surefire-reports\", "
                  + "\"archive_download_url\": \"x\", \"expired\": false}]}";
          TestHttpServer.respond(
              exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8), Map.of());
        });

    List<Object> artifacts = client(null).listArtifacts("o", "r", 9);

    assertThat(sawAuth).isFalse();
    assertThat(artifacts).hasSize(1);
  }

  @Test
  void nonOkStatusBecomesAGitHubApiExceptionWithTheStatusCode() {
    server.json("/repos/o/r/actions/workflows/ci.yml/runs", 404, "{\"message\": \"Not Found\"}");

    assertThatThrownBy(() -> client(null).listWorkflowRunsPage("o", "r", "ci.yml", 1, 100))
        .isInstanceOf(GitHubApiException.class)
        .hasMessageContaining("404")
        .extracting(e -> ((GitHubApiException) e).statusCode())
        .isEqualTo(404);
  }

  @Test
  void malformedJsonBecomesAGitHubApiException() {
    server.json("/repos/o/r/actions/workflows/ci.yml/runs", 200, "not json");

    assertThatThrownBy(() -> client(null).listWorkflowRunsPage("o", "r", "ci.yml", 1, 100))
        .isInstanceOf(GitHubApiException.class);
  }

  @Test
  void aResponseThatIsNotAJsonObjectIsRejected() {
    server.json("/repos/o/r/actions/workflows/ci.yml/runs", 200, "[1, 2, 3]");

    assertThatThrownBy(() -> client(null).listWorkflowRunsPage("o", "r", "ci.yml", 1, 100))
        .isInstanceOf(GitHubApiException.class)
        .hasMessageContaining("expected a JSON object");
  }

  @Test
  void downloadRequiresATokenAndNeverHitsTheNetworkWithoutOne() {
    server.handle(
        "/never",
        exchange -> {
          throw new AssertionError("should not have been called");
        });

    assertThatThrownBy(() -> client(null).downloadArtifact(server.baseUri() + "/never"))
        .isInstanceOf(GitHubApiException.class)
        .hasMessageContaining("GITHUB_TOKEN");
  }

  @Test
  void downloadFollowsARedirectOnceWithoutForwardingTheToken() {
    server.handle(
        "/artifact",
        exchange -> {
          if (!"Bearer secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            TestHttpServer.respond(exchange, 401, "text/plain", new byte[0], Map.of());
            return;
          }
          TestHttpServer.respond(
              exchange,
              302,
              "text/plain",
              new byte[0],
              Map.of("Location", server.baseUri() + "/blob"));
        });
    server.handle(
        "/blob",
        exchange -> {
          if (exchange.getRequestHeaders().containsKey("Authorization")) {
            TestHttpServer.respond(
                exchange,
                403,
                "text/plain",
                "token leaked".getBytes(StandardCharsets.UTF_8),
                Map.of());
            return;
          }
          TestHttpServer.respond(exchange, 200, "application/zip", new byte[] {1, 2, 3}, Map.of());
        });

    byte[] content = client("secret").downloadArtifact(server.baseUri() + "/artifact");

    assertThat(content).containsExactly(1, 2, 3);
  }

  @Test
  void downloadFailsClearlyWhenTheFinalResponseIsNotOk() {
    server.json("/broken", 500, "{}");

    assertThatThrownBy(() -> client("secret").downloadArtifact(server.baseUri() + "/broken"))
        .isInstanceOf(GitHubApiException.class)
        .hasMessageContaining("500");
  }

  @Test
  void redirectWithoutALocationHeaderFailsClearly() {
    server.handle(
        "/artifact",
        exchange -> TestHttpServer.respond(exchange, 302, "text/plain", new byte[0], Map.of()));

    assertThatThrownBy(() -> client("secret").downloadArtifact(server.baseUri() + "/artifact"))
        .isInstanceOf(GitHubApiException.class)
        .hasMessageContaining("Location");
  }

  @Test
  void connectionFailureBecomesAGitHubApiExceptionWithZeroStatus() {
    server.close();

    assertThatThrownBy(() -> client(null).listWorkflowRunsPage("o", "r", "ci.yml", 1, 100))
        .isInstanceOf(GitHubApiException.class)
        .extracting(e -> ((GitHubApiException) e).statusCode())
        .isEqualTo(0);
  }
}
