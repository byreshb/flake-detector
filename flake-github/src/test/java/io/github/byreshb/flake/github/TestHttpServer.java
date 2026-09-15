package io.github.byreshb.flake.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/** A tiny loopback HTTP server for exercising {@link GitHubClient}'s real HTTP behaviour. */
final class TestHttpServer implements AutoCloseable {

  private final HttpServer server;

  private TestHttpServer(HttpServer server) {
    this.server = server;
  }

  static TestHttpServer start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newCachedThreadPool());
      server.start();
      return new TestHttpServer(server);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /** Registers a handler that always answers with a fixed status, JSON body and headers. */
  void json(String path, int status, String body) {
    handle(
        path,
        exchange ->
            respond(
                exchange,
                status,
                "application/json",
                body.getBytes(StandardCharsets.UTF_8),
                Map.of()));
  }

  /** Registers a handler that answers with a fixed status, body, content type and headers. */
  void handle(String path, HttpHandler handler) {
    server.createContext(path, handler);
  }

  static void respond(
      HttpExchange exchange,
      int status,
      String contentType,
      byte[] body,
      Map<String, String> headers)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
    exchange.sendResponseHeaders(status, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  @Override
  public void close() {
    try {
      server.stop(0);
    } catch (RuntimeException alreadyStopped) {
      // A test may stop the server itself to simulate a connection failure.
    }
  }
}
