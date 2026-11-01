package edu.northeastern.cs6510.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.Messaging;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Stack;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * US4-1, FR-026 and 005 FR-019: the low-stock view reports physical stock with the catalog name, ignores
 * reservations, and keeps its names from the event-built copy, through a Catalog outage and a rebuild.
 */
@Tag("system")
class LowStockViewTest {
  private static final String SKU = "SKU-000020";
  private final PublicApi api = new PublicApi();
  private final HttpClient http = HttpClient.newHttpClient();
  private int originalStock;

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @AfterEach
  void restore() {
    Stack.start("catalog");
    Stack.awaitReady("catalog", Duration.ofSeconds(120));
    Stack.awaitProjectionCurrent("inventory", Duration.ofSeconds(90));
    if (originalStock > 0) InventoryProvisioning.setStock(SKU, originalStock);
  }

  @Test
  void lowStockShowsPhysicalStockWithTheCatalogNameAndNeverTheSku() throws Exception {
    originalStock = InventoryProvisioning.position(SKU).physical();
    InventoryProvisioning.setStock(SKU, 5);
    String catalogName = catalogName(SKU);

    JsonNode alert = alertFor(api.lowStock(10));
    assertEquals(catalogName, alert.get("name").asText());
    assertNotEquals(SKU, alert.get("name").asText());
    assertEquals(5, alert.get("currentStock").asInt());
    assertEquals(10, alert.get("threshold").asInt());
    assertNotNull(alert.get("triggeredAt").asText());

    // Reservations arrive as broker commands since 005; the reservation HTTP API is gone.
    String attemptId = UUID.randomUUID().toString();
    InventoryReplyV1 reserved = Messaging.reserve(attemptId, SKU, 3);
    assertEquals(InventoryReplyV1.RESERVED, reserved.messageType(), String.valueOf(reserved.error()));
    try {
      assertEquals(5, alertFor(api.lowStock(10)).get("currentStock").asInt(), "a reservation is not a decrement");
    } finally {
      assertEquals(InventoryReplyV1.RELEASED, Messaging.cancel(attemptId).messageType());
    }

    // Names come from Inventory's copy of catalog.products.v1: Catalog being away changes nothing,
    // and a rebuild replays the same names (005 US5, FR-019).
    Stack.stop("catalog");
    assertEquals(catalogName, alertFor(api.lowStock(10)).get("name").asText(), "no Catalog call at read time");
    HttpResponse<String> rebuild = inventory("POST", "/internal/v1/ops/catalog-projection/rebuild", null);
    assertEquals(202, rebuild.statusCode(), rebuild.body());
    Stack.awaitProjectionCurrent("inventory", Duration.ofSeconds(60));
    PublicApi.Response replayed = awaitLowStock(r -> r.status() == 200, Duration.ofSeconds(30));
    assertEquals(catalogName, alertFor(replayed).get("name").asText());
  }

  private JsonNode alertFor(PublicApi.Response response) {
    assertEquals(200, response.status(), String.valueOf(response.body()));
    for (JsonNode alert : response.body().get("alerts")) if (alert.get("sku").asText().equals(SKU)) return alert;
    fail(SKU + " is not in " + response.body());
    return null;
  }

  private String catalogName(String sku) {
    for (JsonNode item : api.items().body().get("items")) if (item.get("sku").asText().equals(sku)) return item.get("name").asText();
    fail(sku + " is not in the catalog");
    return null;
  }

  private PublicApi.Response awaitLowStock(Predicate<PublicApi.Response> done, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    PublicApi.Response last = null;
    while (Instant.now().isBefore(deadline)) {
      last = api.lowStock(10);
      if (done.test(last)) return last;
      Thread.sleep(1000);
    }
    fail("low-stock view did not change as expected within " + timeout + ": " + last.status() + " " + last.body());
    return null;
  }

  private HttpResponse<String> inventory(String method, String path, String json) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(Stack.SERVICE_URLS.get("inventory") + path)).timeout(Duration.ofSeconds(10));
    request.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
    if (json != null) request.header("Content-Type", "application/json");
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
