package edu.northeastern.cs6510.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.AnalyticsOps;
import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import edu.northeastern.cs6510.support.Workload;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 005 quickstart Q5, US3, SC-005, SC-006: one service down at a time. Only what depends on it
 * degrades, and it catches up when it returns. The 20 s Inventory case is {@code LateReplyTest}.
 */
@Tag("system")
class ServiceOutageTest {
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void withCatalogDownEveryScanStillSucceedsFromTheLocalCopy() throws Exception {
    Stack.stop("catalog");
    Workload workload = Workload.start(3, 3, "catalog-down");
    try {
      Thread.sleep(60_000);
    } finally {
      workload.close();
      Stack.start("catalog");
      Stack.awaitReady("catalog", Duration.ofSeconds(120));
    }
    assertTrue(workload.count("SCAN 200") > 0, String.valueOf(workload.statuses()));
    assertEquals(0, workload.statuses().keySet().stream().filter(k -> k.startsWith("SCAN ") && !k.equals("SCAN 200")).count(),
        "every scan of a published item succeeds while Catalog is away: " + workload.statuses());
    Reconciliation.assertPass();
  }

  @Test
  void withAnalyticsDownCheckoutWorksAndAnalyticsCatchesUp() throws Exception {
    Stack.stop("analytics");
    Workload workload = Workload.start(3, 3, "analytics-down");
    try {
      Thread.sleep(60_000);
    } finally {
      workload.close();
      Stack.start("analytics");
      Stack.awaitReady("analytics", Duration.ofSeconds(120));
    }
    assertTrue(workload.count("COMPLETE 200") > 0, String.valueOf(workload.statuses()));
    assertEquals(0, workload.statuses().keySet().stream().filter(k -> !k.endsWith(" 200") && !k.endsWith(" 201")).count(),
        "no public error while Analytics is away: " + workload.statuses());
    AnalyticsOps.awaitDrained(Duration.ofSeconds(60));
    Reconciliation.assertPass();   // R-6: every accepted scan counted once
  }

  @Test
  void withInventoryDownPastCancelAfterTheAttemptIsCancelledAndTheBasketStaysPurchasable() throws Exception {
    String sku = "SKU-000075";
    InventoryProvisioning.setStock(sku, 10);
    String tx = api.start("inventory-60s").text("transactionId");
    assertEquals(200, api.scan(tx, sku).status());

    Stack.stop("inventory");
    try {
      assertEquals(503, api.complete(tx).status());
      Thread.sleep(60_000);
    } finally {
      Stack.start("inventory");
      Stack.awaitReady("inventory", Duration.ofSeconds(120));
    }

    List<CheckoutDb.Attempt> attempts = CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
    assertEquals("CANCELLED", attempts.get(0).state(), "waited past cancel-after (30 s) before any reply");
    assertEquals(10, InventoryProvisioning.position(sku).physical(), "no stock change");
    assertEquals(0, InventoryProvisioning.position(sku).reserved());
    assertEquals(1, CheckoutDb.basketUnits(tx), "the basket is still open with its item");

    assertEquals(200, api.complete(tx).status(), "a new completion succeeds once Inventory is back");
    Reconciliation.assertPass();
  }
}
