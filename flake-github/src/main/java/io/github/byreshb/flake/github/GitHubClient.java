package io.github.byreshb.flake.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A thin REST client for the parts of the GitHub API this project needs: listing workflow runs and
 * artifacts, and downloading an artifact's zip. Authenticates with a personal access token or the
 * {@code GITHUB_TOKEN} a GitHub Actions job is given; requests work without one against public
 * repositories, except downloading an artifact, which GitHub always requires a token for.
 */
public final class GitHubClient {

  /** The real GitHub REST API. */
  public static final URI DEFAULT_API_BASE = URI.create("https://api.github.com");

  private static final String USER_AGENT = "flake-detector";
  private static final String API_VERSION = "2022-11-28";

  private final URI apiBase;
  private final String token;
  private final HttpClient http;

  /**
   * Creates a client against a specific API base, for pointing at a test server.
   *
   * @param apiBase the API root, e.g. {@link #DEFAULT_API_BASE}
   * @param token bearer token, or null/blank for unauthenticated requests
   * @param http the HTTP client to send requests with; redirects are followed manually, so its
   *     redirect policy does not matter
   */
  public GitHubClient(URI apiBase, String token, HttpClient http) {
    this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
    this.token = token;
    this.http = Objects.requireNonNull(http, "http");
  }

  /**
   * Creates a client for the real GitHub API.
   *
   * @param token bearer token, or null/blank for unauthenticated requests
   * @return the client
   */
  public static GitHubClient create(String token) {
    return new GitHubClient(
        DEFAULT_API_BASE,
        token,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  /**
   * Sends a {@code GET} and parses the response body as JSON.
   *
   * @param path an absolute path (starting with {@code /}) resolved against the API base
   * @return the parsed body: a {@code Map}, a {@code List}, or a scalar
   * @throws GitHubApiException when the request fails, the response is not 200, or the body is not
   *     valid JSON
   */
  @SuppressWarnings("unchecked")
  Map<String, Object> getJsonObject(String path) {
    Object value = getJson(path);
    if (!(value instanceof Map)) {
      throw new GitHubApiException(200, path + ": expected a JSON object, got " + value, null);
    }
    return (Map<String, Object>) value;
  }

  private Object getJson(String path) {
    HttpRequest request = requestBuilder(apiBase.resolve(path)).GET().build();
    HttpResponse<String> response = send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new GitHubApiException(
          response.statusCode(),
          "GET " + path + " returned " + response.statusCode() + ": " + snippet(response.body()),
          null);
    }
    try {
      return Json.parse(response.body());
    } catch (JsonFormatException e) {
      throw new GitHubApiException(response.statusCode(), path + ": " + e.getMessage(), e);
    }
  }

  /**
   * Downloads an artifact's content. GitHub's artifact endpoint always answers with a redirect to a
   * short-lived, unauthenticated URL; the redirect is followed once, without forwarding the bearer
   * token to it.
   *
   * @param archiveDownloadUrl the artifact's {@code archive_download_url}
   * @return the zip file's bytes
   * @throws GitHubApiException when no token is configured, the request fails, or the response is
   *     not 200 (after following at most one redirect)
   */
  public byte[] downloadArtifact(String archiveDownloadUrl) {
    if (token == null || token.isBlank()) {
      throw new GitHubApiException(
          0, "downloading an artifact requires a token (set GITHUB_TOKEN)", null);
    }
    HttpRequest request = requestBuilder(URI.create(archiveDownloadUrl)).GET().build();
    HttpResponse<byte[]> response = send(request, BodyHandlers.ofByteArray());
    int status = response.statusCode();
    if (status >= 300 && status < 400) {
      int redirectStatus = status;
      String location =
          response
              .headers()
              .firstValue("Location")
              .orElseThrow(
                  () ->
                      new GitHubApiException(
                          redirectStatus,
                          "redirect from " + archiveDownloadUrl + " had no Location",
                          null));
      HttpRequest redirected =
          HttpRequest.newBuilder(URI.create(location))
              .header("User-Agent", USER_AGENT)
              .GET()
              .build();
      response = send(redirected, BodyHandlers.ofByteArray());
      status = response.statusCode();
    }
    if (status != 200) {
      throw new GitHubApiException(
          status, "downloading " + archiveDownloadUrl + " returned " + status, null);
    }
    return response.body();
  }

  private HttpRequest.Builder requestBuilder(URI uri) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION);
    if (token != null && !token.isBlank()) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder;
  }

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    try {
      return http.send(request, handler);
    } catch (IOException e) {
      throw new GitHubApiException(0, request.uri() + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubApiException(0, request.uri() + ": interrupted", e);
    }
  }

  private static String snippet(String body) {
    if (body == null) {
      return "";
    }
    return body.length() > 300 ? body.substring(0, 300) + "..." : body;
  }

  /**
   * Package-visible accessor used by {@link GitHubArtifactsSource} to list workflow runs.
   *
   * @param owner repository owner
   * @param repo repository name
   * @param workflow workflow file name (e.g. {@code ci.yml}) or numeric id
   * @param page page number, starting at 1
   * @param perPage page size, at most 100
   * @return the raw {@code workflow_runs} array, empty when the page is past the end
   */
  @SuppressWarnings("unchecked")
  List<Object> listWorkflowRunsPage(
      String owner, String repo, String workflow, int page, int perPage) {
    Map<String, Object> body =
        getJsonObject(
            String.format(
                "/repos/%s/%s/actions/workflows/%s/runs?per_page=%d&page=%d",
                owner, repo, workflow, perPage, page));
    Object runs = body.get("workflow_runs");
    return runs == null ? List.of() : (List<Object>) runs;
  }

  /**
   * Package-visible accessor used by {@link GitHubArtifactsSource} to list a run's artifacts.
   *
   * @param owner repository owner
   * @param repo repository name
   * @param runId the workflow run id
   * @return the raw {@code artifacts} array
   */
  @SuppressWarnings("unchecked")
  List<Object> listArtifacts(String owner, String repo, long runId) {
    Map<String, Object> body =
        getJsonObject(
            String.format(
                "/repos/%s/%s/actions/runs/%d/artifacts?per_page=100", owner, repo, runId));
    Object artifacts = body.get("artifacts");
    return artifacts == null ? List.of() : (List<Object>) artifacts;
  }
}
