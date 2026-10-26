package edu.northeastern.cs6510.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V4, gate 3, SC-001: every response of the seven public operations, success and error,
 * conforms to {@code spec/self-checkout-openapi.yaml} v1.2.1, and every code of the error catalog in
 * {@code contracts/public-api-amendment.md} is produced with its documented status.
 */
@Tag("system")
class FullGatewayContractTest {
  private static final Set<String> STATUSES = Set.of("OPEN", "COMPLETED", "CANCELLED");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static OpenApiInteractionValidator validator;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /** One validated response: status, raw body and its parsed JSON. */
  private record Call(int status, String body, JsonNode json) {
    String error() { return json != null && json.has("error") ? json.get("error").asText() : null; }
    String text(String field) { return json != null && json.has(field) ? json.get(field).asText() : null; }
  }

  @BeforeAll
  static void setUp() throws IOException {
    Stack.requireRunning();
    String spec = Files.readString(Path.of("..", "..", "spec", "self-checkout-openapi.yaml"));
    assertTrue(spec.contains("version: \"1.2.1\""), "the amended public contract is v1.2.1");
    validator = OpenApiInteractionValidator.createForInlineApiSpecification(spec).build();
  }

  @Test
  void allSevenOperationsConformAndShowStatusesAndCatalogNames() {
    Map<String, String> names = catalogNames(get("/items"));

    String sku = "SKU-000041";
    int original = InventoryProvisioning.position(sku).physical();
    InventoryProvisioning.setStock(sku, 3);
    try {
      Call started = post("/transactions", "{\"stationId\":\"contract\"}");
      assertEquals(2, started.status() / 100, started.body());
      String tx = started.text("transactionId");
      assertStatus(started.json());
      assertEquals(200, post("/transactions/" + tx + "/items", "{\"sku\":\"" + sku + "\"}").status());
      Call open = get("/transactions/" + tx);
      assertEquals("OPEN", open.text("status"));
      assertStatus(open.json());
      assertEquals(200, post("/transactions/" + tx + "/complete", null).status());
      Call completed = get("/transactions/" + tx);
      assertEquals("COMPLETED", completed.text("status"));
      assertStatus(completed.json());

      Call low = get("/inventory/low-stock?threshold=10");
      assertEquals(200, low.status());
      boolean listed = false;
      for (JsonNode alert : low.json().get("alerts")) {
        assertEquals(names.get(alert.get("sku").asText()), alert.get("name").asText(), "low-stock name is the catalog name");
        listed |= alert.get("sku").asText().equals(sku);
      }
      assertTrue(listed, sku + " (physical stock 2) is a low-stock alert");

      JsonNode popular = awaitPopularItems();
      for (JsonNode item : popular.get("items")) {
        assertEquals(names.get(item.get("sku").asText()), item.get("name").asText(), "popular-items name is the catalog name");
      }
    } finally {
      InventoryProvisioning.setStock(sku, original);
    }
  }

  @Test
  void everyClientErrorCodeHasItsDocumentedStatus() {
    String unknown = UUID.randomUUID().toString();
    expect(post("/transactions", "{\"stationId\":\"  \"}"), 400, "MISSING_STATION_ID");
    expect(post("/transactions", "{}"), 400, "MISSING_STATION_ID");
    String tx = post("/transactions", "{\"stationId\":\"contract-errors\"}").text("transactionId");
    expect(post("/transactions/" + unknown + "/items", "{\"sku\":\"SKU-000001\"}"), 404, "TRANSACTION_NOT_FOUND");
    expect(post("/transactions/" + unknown + "/complete", null), 404, "TRANSACTION_NOT_FOUND");
    expect(get("/transactions/" + unknown), 404, "TRANSACTION_NOT_FOUND");
    expect(post("/transactions/" + tx + "/items", "{\"sku\":\"SKU-999999\"}"), 404, "SKU_NOT_FOUND");
    expect(post("/transactions/" + tx + "/complete", null), 409, "EMPTY_BASKET");

    String gone = "SKU-000042";
    int original = InventoryProvisioning.position(gone).physical();
    InventoryProvisioning.setStock(gone, 0);
    try {
      assertEquals(200, post("/transactions/" + tx + "/items", "{\"sku\":\"" + gone + "\"}").status(), "a scan never checks stock");
      expect(post("/transactions/" + tx + "/complete", null), 409, "INSUFFICIENT_STOCK");
      assertEquals("OPEN", get("/transactions/" + tx).text("status"), "an unavailable basket stays open");
    } finally {
      InventoryProvisioning.setStock(gone, original);
    }
    assertEquals(200, post("/transactions/" + tx + "/complete", null).status(), "restocked: the retained basket is purchased");
    expect(post("/transactions/" + tx + "/items", "{\"sku\":\"SKU-000001\"}"), 409, "TRANSACTION_NOT_OPEN");
    expect(post("/transactions/" + tx + "/complete", null), 409, "TRANSACTION_NOT_OPEN");
  }

  /** The {@code 400} responses on scan and popular items, documented by amendment A7 (v1.2.1). */
  @Test
  void badRequestsOnScanAndPopularItemsAreDocumented() {
    String tx = post("/transactions", "{\"stationId\":\"contract-400\"}").text("transactionId");
    expect(post("/transactions/" + tx + "/items", "{}"), 400, "MISSING_SKU");
    expect(get("/analytics/popular-items?limit=0"), 400, "INVALID_LIMIT");
  }

  @Test
  void busyScanAndUnconfirmedCompletion() throws Exception {
    String sku = "SKU-000043";
    InventoryProvisioning.setStock(sku, 5);
    String tx = post("/transactions", "{\"stationId\":\"contract-busy\"}").text("transactionId");
    assertEquals(200, post("/transactions/" + tx + "/items", "{\"sku\":\"" + sku + "\"}").status());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Stack.pause("inventory");
    try {
      Future<Call> completion = pool.submit(() -> post("/transactions/" + tx + "/complete", null));
      CheckoutDb.awaitActiveAttempt(tx, Duration.ofSeconds(5));
      expect(post("/transactions/" + tx + "/items", "{\"sku\":\"" + sku + "\"}"), 409, "TRANSACTION_BUSY");
      expect(completion.get(), 503, "SERVICE_UNAVAILABLE");   // A4: outcome not confirmed within the deadline
    } finally {
      Stack.unpause("inventory");
      pool.shutdownNow();
    }
    CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
    Reconciliation.assertPass();
  }

  @Test
  void everyOperationAnswers503WhenItsOwnerIsDown() {
    String sku = "SKU-000044";   // its own SKU: other tests change the stock of common ones
    InventoryProvisioning.setStock(sku, 5);
    String tx = post("/transactions", "{\"stationId\":\"contract-503\"}").text("transactionId");
    assertEquals(200, post("/transactions/" + tx + "/items", "{\"sku\":\"" + sku + "\"}").status());

    whileStopped("catalog", () -> expect(get("/items"), 503, "SERVICE_UNAVAILABLE"));
    whileStopped("inventory", () -> expect(get("/inventory/low-stock"), 503, "SERVICE_UNAVAILABLE"));
    whileStopped("analytics", () -> expect(get("/analytics/popular-items"), 503, "SERVICE_UNAVAILABLE"));
    whileStopped("checkout", () -> {
      expect(post("/transactions", "{\"stationId\":\"contract-503\"}"), 503, "SERVICE_UNAVAILABLE");
      expect(post("/transactions/" + tx + "/items", "{\"sku\":\"" + sku + "\"}"), 503, "SERVICE_UNAVAILABLE");
      expect(get("/transactions/" + tx), 503, "SERVICE_UNAVAILABLE");
      expect(post("/transactions/" + tx + "/complete", null), 503, "SERVICE_UNAVAILABLE");
    });
    Call completed = post("/transactions/" + tx + "/complete", null);
    assertEquals(200, completed.status(), "the basket survives the outage: " + completed.body());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void whileStopped(String service, Runnable checks) {
    Stack.stop(service);
    try {
      checks.run();
    } finally {
      Stack.start(service);
      Stack.awaitReady(service, Duration.ofSeconds(120));
      if (!service.equals("analytics")) Stack.awaitReady("gateway", Duration.ofSeconds(30));
    }
  }

  private static void expect(Call call, int status, String error) {
    assertEquals(status, call.status(), call.body());
    assertEquals(error, call.error(), call.body());
  }

  private static void assertStatus(JsonNode transaction) {
    assertTrue(STATUSES.contains(transaction.get("status").asText()), "status " + transaction.get("status"));
  }

  private static Map<String, String> catalogNames(Call items) {
    assertEquals(200, items.status());
    Map<String, String> names = new HashMap<>();
    for (JsonNode item : items.json().get("items")) names.put(item.get("sku").asText(), item.get("name").asText());
    assertFalse(names.isEmpty());
    return names;
  }

  /** Popular items once at least one window is published (the first publication happens at scan 1). */
  private JsonNode awaitPopularItems() {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      Call call = get("/analytics/popular-items?limit=50");
      if (call.status() == 200 && call.json().get("items").size() > 0) return call.json();
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    fail("no popular-items window was published within 60 s");
    return null;
  }

  private Call get(String pathAndQuery) { return send("GET", pathAndQuery, null); }

  private Call post(String path, String body) { return send("POST", path, body); }

  /** Sends one request through the gateway and validates the response against the OpenAPI document. */
  private Call send(String method, String pathAndQuery, String body) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(Stack.GATEWAY + pathAndQuery)).timeout(Duration.ofSeconds(15));
    if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
    else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(method + " " + pathAndQuery + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
    SimpleResponse.Builder documented = SimpleResponse.Builder.status(response.statusCode()).withBody(response.body());
    response.headers().firstValue("Content-Type").ifPresent(documented::withContentType);
    ValidationReport report = validator.validateResponse(path, Request.Method.valueOf(method), documented.build());
    assertFalse(report.hasErrors(), () -> method + " " + pathAndQuery + " -> " + response.statusCode() + " does not conform: "
        + report.getMessages().stream().map(ValidationReport.Message::getMessage).collect(Collectors.joining("; "))
        + "\nbody: " + response.body());
    JsonNode json = null;
    try {
      if (!response.body().isBlank()) json = JSON.readTree(response.body());
    } catch (IOException notJson) {
      fail(method + " " + pathAndQuery + " returned a non-JSON body: " + response.body());
    }
    return new Call(response.statusCode(), response.body(), json);
  }
}
