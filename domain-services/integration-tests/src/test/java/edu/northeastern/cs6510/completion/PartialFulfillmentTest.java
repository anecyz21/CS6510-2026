package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.InventoryProvisioning.Position;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Quickstart V5 steps 1–5: partial purchase, per-attempt receipts, zero-stock 409 and a later purchase (US1-1, -3, -4, -6). */
@Tag("system")
class PartialFulfillmentTest {
  private static final String A = "SKU-000001";
  private static final String B = "SKU-000002";
  private static final String ZERO = "SKU-000003";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void purchasesAvailableUnitsAndKeepsTheRestInTheOpenBasket() {
    InventoryProvisioning.setStock(A, 2);
    InventoryProvisioning.setStock(B, 1);
    InventoryProvisioning.setStock(ZERO, 0);
    Map<String, Position> before = positions();

    // Step 1: every scan is accepted, even with zero stock, and never touches stock (US1-1).
    Response started = api.start("v5-partial");
    assertEquals(201, started.status());
    String tx = started.text("transactionId");
    Map<String, BigDecimal> price = new HashMap<>();
    for (String sku : new String[] {A, A, A, B, ZERO}) {
      Response scan = api.scan(tx, sku);
      assertEquals(200, scan.status(), scan.body().toString());
      price.put(sku, scan.body().get("unitPrice").decimalValue());
    }
    assertEquals(5, api.transaction(tx).body().get("itemCount").asInt());
    assertEquals(before, positions(), "scans must not reserve or decrement stock");

    // Step 2: the first completion buys what is available; the shortfall stays in the open basket (US1-3).
    Response first = api.complete(tx);
    assertEquals(200, first.status(), String.valueOf(first.body()));
    assertEquals(Map.of(A, 2, B, 1), quantities(first.body()));
    assertEquals(3, first.body().get("itemCount").asInt());
    assertMoney(price.get(A).multiply(BigDecimal.valueOf(2)).add(price.get(B)), first.body().get("totalAmount"));
    JsonNode afterFirst = api.transaction(tx).body();
    assertEquals("OPEN", afterFirst.get("status").asText());
    assertEquals(2, afterFirst.get("itemCount").asInt());   // A×1 and ZERO×1 remain

    // Step 3: nothing remaining is available, so nothing changes (US1-6).
    Map<String, Position> beforeRejected = positions();
    Response rejected = api.complete(tx);
    assertEquals(409, rejected.status());
    assertEquals("INSUFFICIENT_STOCK", rejected.error());
    assertEquals(2, api.transaction(tx).body().get("itemCount").asInt());
    assertEquals(beforeRejected, positions());

    // Step 4: after restocking, a later completion buys only the remaining units (US1-4).
    InventoryProvisioning.addStock(A, 1);
    InventoryProvisioning.addStock(ZERO, 1);
    Response second = api.complete(tx);
    assertEquals(200, second.status(), String.valueOf(second.body()));
    assertEquals(Map.of(A, 1, ZERO, 1), quantities(second.body()));
    assertEquals(2, second.body().get("itemCount").asInt());
    assertMoney(price.get(A).add(price.get(ZERO)), second.body().get("totalAmount"));
    assertEquals(tx, second.text("transactionId"));
    assertEquals(first.text("startedAt"), second.text("startedAt"));
    assertNotEquals(first.text("completedAt"), second.text("completedAt"));
    assertEquals("COMPLETED", api.transaction(tx).body().get("status").asText());

    // Step 5: a completed transaction is not open (US2-3).
    Response closed = api.complete(tx);
    assertEquals(409, closed.status());
    assertEquals("TRANSACTION_NOT_OPEN", closed.error());

    Reconciliation.assertPass();
  }

  private static Map<String, Position> positions() {
    Map<String, Position> p = new HashMap<>();
    for (String sku : new String[] {A, B, ZERO}) p.put(sku, InventoryProvisioning.position(sku));
    return p;
  }

  private static Map<String, Integer> quantities(JsonNode receipt) {
    Map<String, Integer> q = new HashMap<>();
    receipt.get("lines").forEach(l -> q.put(l.get("sku").asText(), l.get("quantity").asInt()));
    return q;
  }

  private static void assertMoney(BigDecimal expected, JsonNode actual) {
    assertEquals(0, expected.compareTo(actual.decimalValue()), "expected " + expected + " but was " + actual);
  }
}
