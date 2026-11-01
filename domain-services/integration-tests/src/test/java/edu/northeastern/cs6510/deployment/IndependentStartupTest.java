package edu.northeastern.cs6510.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V1 (US3-1, US3-3, gate 1; 005 Q1): every service starts, migrates and becomes ready with only
 * its own database and the broker, reports its dependencies truthfully, and can be redeployed alone.
 */
@Tag("system")
class IndependentStartupTest {
  private static final List<String> SERVICES = List.of("catalog", "checkout", "inventory", "analytics");
  private static final List<String> APPS = List.of("gateway", "catalog", "checkout", "inventory", "analytics");

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @AfterAll
  static void restoreTheWholeStack() {
    Stack.compose("up", "-d");
    for (String service : SERVICES) Stack.awaitReady(service, Duration.ofSeconds(180));
    Stack.awaitReady("gateway", Duration.ofSeconds(120));
  }

  /**
   * 005: a service needs only its own database and the broker. With every other domain service
   * stopped, Catalog included, each one becomes ready: Checkout, Inventory and Analytics rebuild
   * readiness from the catalog topic Kafka retains. Checkout no longer checks Inventory's health at
   * all, because it never calls Inventory.
   */
  @Test
  void eachServiceBecomesReadyWithOnlyItsOwnDatabaseAndTheBroker() {
    Stack.compose(withServices("stop", allContainers()));
    try {
      Stack.compose("up", "-d", "--no-deps", "kafka");
      Stack.awaitHealthy("kafka", Duration.ofSeconds(120));
      for (String service : SERVICES) {
        Stack.compose("up", "-d", "--no-deps", service + "-db", service);
        Stack.awaitReady(service, Duration.ofSeconds(180));
        JsonNode components = new PublicApi(Stack.SERVICE_URLS.get(service)).get("/actuator/health").body().get("components");
        assertEquals("UP", components.get("db").get("status").asText());
        assertEquals("UP", components.get("kafka").get("status").asText());
        if (!service.equals("catalog")) {
          assertEquals("CURRENT", components.get("catalogProjection").get("details").get("state").asText(),
              service + " caught up from the topic with Catalog stopped");
        }
        if (service.equals("checkout")) {
          assertTrue(components.get("inventory") == null, "Checkout has no Inventory dependency to report");
        }
        Stack.compose("stop", service, service + "-db");
      }
    } finally {
      Stack.compose("up", "-d");
    }
  }

  @Test
  void redeployingOneServiceRestartsNoOtherContainer() {
    for (String service : SERVICES) Stack.awaitReady(service, Duration.ofSeconds(180));
    Map<String, String> before = startedAt();

    Stack.compose("up", "-d", "--no-deps", "--force-recreate", "inventory");
    Stack.awaitReady("inventory", Duration.ofSeconds(180));

    Map<String, String> after = startedAt();
    for (String container : before.keySet()) {
      if (container.equals("inventory")) continue;
      assertEquals(before.get(container), after.get(container), container + " must not restart when Inventory is redeployed");
    }
    assertNotEquals(before.get("inventory"), after.get("inventory"), "Inventory was redeployed");

    PublicApi api = new PublicApi();
    InventoryProvisioning.setStock("SKU-000090", 10);
    String tx = api.start("after-redeploy").text("transactionId");
    assertEquals(200, api.scan(tx, "SKU-000090").status());
    assertEquals(200, api.complete(tx).status(), "supported contracts keep working after a lone redeploy");
  }

  /** StartedAt of every running container in the project, keyed by service name. */
  private static Map<String, String> startedAt() {
    Map<String, String> out = new HashMap<>();
    for (String line : Stack.compose("ps", "--format", "{{.Service}} {{.ID}}").strip().split("\\R")) {
      String[] parts = line.strip().split("\\s+");
      if (parts.length < 2) continue;
      out.put(parts[0], docker("inspect", "-f", "{{.State.StartedAt}}", parts[1]).strip());
    }
    return out;
  }

  private static String[] allContainers() {
    List<String> names = new ArrayList<>(APPS);
    for (String s : SERVICES) names.add(s + "-db");
    return names.toArray(String[]::new);
  }

  private static String[] withServices(String command, String[] services) {
    String[] args = new String[services.length + 1];
    args[0] = command;
    System.arraycopy(services, 0, args, 1, services.length);
    return args;
  }

  private static String docker(String... args) {
    List<String> command = new ArrayList<>(List.of("docker"));
    command.addAll(List.of(args));
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      return out;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
