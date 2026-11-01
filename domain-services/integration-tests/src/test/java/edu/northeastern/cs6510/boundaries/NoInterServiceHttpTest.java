package edu.northeastern.cs6510.boundaries;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Event-only communication between domain services (005 FR-001, FR-002, FR-022, research R10,
 * constitution v4.0.0 Principle III and gate 2). Catalog, Checkout, Inventory and Analytics talk
 * to each other only through Kafka: none may hold an HTTP client, a URL setting, or another
 * domain service's address. The gateway is excluded: forwarding public requests is its job.
 */
class NoInterServiceHttpTest {
  private static final Path DS = Path.of("..").toAbsolutePath().normalize();
  private static final List<String> DOMAINS = List.of("catalog", "checkout", "inventory", "analytics");
  private static final List<String> HTTP_CLIENTS = List.of("java.net.http.HttpClient", "org.springframework.web.client.RestClient",
      "org.springframework.web.client.RestTemplate", "org.springframework.web.reactive.function.client.WebClient");
  private static final Pattern YAML_KEY = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):(.*)$");
  private static final Pattern DOMAIN_ADDRESS = Pattern.compile("https?://(catalog|checkout|inventory|analytics)[:/]");
  private static final Pattern COMPOSE_SERVICE = Pattern.compile("^  ([a-z0-9-]+):\\s*$");

  @Test
  void noDomainServiceUsesAnHttpClient() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String domain : DOMAINS) {
      try (Stream<Path> files = Files.walk(DS.resolve(domain + "-service/src/main/java"))) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file);
          for (String client : HTTP_CLIENTS) {
            String simpleName = client.substring(client.lastIndexOf('.') + 1);
            if (source.contains(client) || source.matches("(?s).*\\b" + simpleName + "\\.(builder|create|newBuilder|newHttpClient)\\b.*")) {
              violations.add(DS.relativize(file) + " uses " + simpleName);
            }
          }
        }
      }
    }
    assertEquals(List.of(), violations, "domain services must reach each other only through the broker");
  }

  @Test
  void noDomainServiceHasAUrlSettingOtherThanItsDatasource() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String domain : DOMAINS) {
      Path yml = DS.resolve(domain + "-service/src/main/resources/application.yml");
      for (String key : yamlKeys(Files.readAllLines(yml))) {
        if (key.endsWith(".url") && !key.equals("spring.datasource.url")) violations.add(domain + ": " + key);
      }
    }
    assertEquals(List.of(), violations, "a URL setting is how one service would learn another's address");
  }

  @Test
  void noDomainServiceIsGivenAnotherServicesAddress() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String compose : List.of("deployment/docker-compose.yml", "deployment/docker-compose.faults.yml")) {
      String service = null;
      boolean inServices = false;
      for (String line : Files.readAllLines(DS.resolve(compose))) {
        if (line.startsWith("services:")) { inServices = true; continue; }
        if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) inServices = false;
        if (!inServices) continue;
        Matcher header = COMPOSE_SERVICE.matcher(line);
        if (header.matches()) { service = header.group(1); continue; }
        if (service != null && DOMAINS.contains(service) && DOMAIN_ADDRESS.matcher(line).find()) {
          violations.add(compose + " " + service + ": " + line.trim());
        }
      }
    }
    assertEquals(List.of(), violations, "only the gateway may hold service addresses");
  }

  /** Dotted paths of the block-style keys in a YAML file (flow mappings are read as one value). */
  static List<String> yamlKeys(List<String> lines) {
    List<String> keys = new ArrayList<>();
    Deque<String[]> stack = new ArrayDeque<>();   // {indent, key}
    for (String line : lines) {
      if (line.isBlank() || line.trim().startsWith("#")) continue;
      Matcher m = YAML_KEY.matcher(line);
      if (!m.matches()) continue;
      int indent = m.group(1).length();
      while (!stack.isEmpty() && Integer.parseInt(stack.peek()[0]) >= indent) stack.pop();
      StringBuilder path = new StringBuilder();
      stack.descendingIterator().forEachRemaining(e -> path.append(e[1]).append('.'));
      path.append(m.group(2));
      keys.add(path.toString());
      stack.push(new String[] {Integer.toString(indent), m.group(2)});
    }
    return keys;
  }
}
