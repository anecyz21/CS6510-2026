package edu.northeastern.cs6510.support;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The running Compose stack under test (quickstart V0). Tests never start it implicitly: start it
 * with {@code domain-services/deployment/scripts/start-services.sh}. Owner credentials come from
 * environment variables or {@code services.env}; only operator/test ports on 127.0.0.1 are used.
 */
public final class Stack {
  public static final String GATEWAY = "http://localhost:8080";
  private static final Path DEPLOYMENT = locateDeployment();
  private static final Map<String, String> ENV = loadEnv();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /** Ops/actuator base URL per application service (never routed by the gateway); honours *_HOST_PORT overrides. */
  public static final Map<String, String> SERVICE_URLS = Map.of(
      "catalog", "http://127.0.0.1:" + env("CATALOG_HOST_PORT", "8081"),
      "checkout", "http://127.0.0.1:" + env("CHECKOUT_HOST_PORT", "8082"),
      "inventory", "http://127.0.0.1:" + env("INVENTORY_HOST_PORT", "8083"),
      "analytics", "http://127.0.0.1:" + env("ANALYTICS_HOST_PORT", "8084"));

  private static final Map<String, Integer> DB_PORTS = Map.of(
      "catalog", 3311, "checkout", 3312, "inventory", 3313, "analytics", 3314);

  private Stack() { }

  /** Fails, never skips, when the stack is not running. */
  public static void requireRunning() {
    try {
      HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(URI.create(GATEWAY + "/actuator/health"))
          .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() == 200) return;
    } catch (IOException | InterruptedException ignored) {
      if (Thread.interrupted()) Thread.currentThread().interrupt();
    }
    fail("start the stack: domain-services/deployment/scripts/start-services.sh");
  }

  /** JDBC URL of a service's private instance, e.g. {@code ownerJdbcUrl("checkout")}. */
  public static String ownerJdbcUrl(String service) {
    return "jdbc:mysql://127.0.0.1:" + DB_PORTS.get(service) + "/" + env(upper(service) + "_DB_NAME", service);
  }

  public static String ownerUser(String service) { return env(upper(service) + "_DB_USERNAME", service + "_service"); }

  public static String ownerPassword(String service) { return env(upper(service) + "_DB_PASSWORD", null); }

  /** Connection to a service's own database with that service's own credentials. */
  public static Connection ownerConnection(String service) throws SQLException {
    return DriverManager.getConnection(ownerJdbcUrl(service), ownerUser(service), ownerPassword(service));
  }

  public static int dbPort(String service) { return DB_PORTS.get(service); }

  /** Runs {@code docker compose <args>} against the stack and returns its stdout; fails on a non-zero exit. */
  public static String compose(String... args) {
    List<String> command = new ArrayList<>(List.of("docker", "compose",
        "-f", DEPLOYMENT.resolve("docker-compose.yml").toString(),
        "--env-file", DEPLOYMENT.resolve("services.env").toString()));
    if (Boolean.getBoolean("stack.faults")) {
      command.addAll(List.of("-f", DEPLOYMENT.resolve("docker-compose.faults.yml").toString()));
    }
    command.addAll(List.of(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) fail("docker compose " + String.join(" ", args) + " failed:\n" + output);
      return output;
    } catch (IOException e) {
      throw new IllegalStateException("docker is not available", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public static void stop(String service) { compose("stop", service); }
  public static void start(String service) { compose("start", service); }
  public static void pause(String service) { compose("pause", service); }
  public static void unpause(String service) { compose("unpause", service); }
  public static void kill(String service) { compose("kill", service); }

  /** Waits until the service's readiness probe (or the gateway's health) answers 200. */
  public static void awaitReady(String service, Duration timeout) {
    String url = "gateway".equals(service) ? GATEWAY + "/actuator/health" : SERVICE_URLS.get(service) + "/actuator/health/readiness";
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      try {
        if (HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return;
      } catch (IOException ignored) {
        // not up yet
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    fail(service + " was not ready within " + timeout);
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) value = ENV.get(key);
    if (value == null || value.isBlank()) value = fallback;
    if (value == null) fail(key + " is not set (environment or domain-services/deployment/services.env)");
    return value;
  }

  private static String upper(String service) { return service.toUpperCase(); }

  private static Path locateDeployment() {
    // Maven runs tests with the module directory as working directory.
    for (Path candidate : List.of(Path.of("..", "deployment"), Path.of("domain-services", "deployment"))) {
      if (Files.exists(candidate.resolve("docker-compose.yml"))) return candidate.toAbsolutePath().normalize();
    }
    return Path.of("..", "deployment").toAbsolutePath().normalize();
  }

  private static Map<String, String> loadEnv() {
    Map<String, String> values = new HashMap<>();
    Path file = DEPLOYMENT.resolve("services.env");
    if (!Files.exists(file)) return values;
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
        int eq = trimmed.indexOf('=');
        values.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + file, e);
    }
    return values;
  }
}
