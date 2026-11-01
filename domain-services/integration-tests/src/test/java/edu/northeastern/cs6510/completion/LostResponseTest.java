package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.Faults;
import edu.northeastern.cs6510.support.InventoryDb;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V7 e: Inventory commits but Checkout never sees the response. The request answers
 * 503 within the deadline, recovery finalizes exactly once, and a retry never resells (US2-2, US2-3).
 */
@Tag("system")
@Tag("needs-us2")   // Toxiproxy-based until 005 task T051 re-points it at the messaging faults
class LostResponseTest {
  private static final String SKU = "SKU-000060";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void lostCommitResponseIsFinalizedOnceByRecovery() {
    InventoryProvisioning.setStock(SKU, 10);
    String tx = api.start("lost-response").text("transactionId");
    assertEquals(200, api.scan(tx, SKU).status());
    assertEquals(200, api.scan(tx, SKU).status());

    Faults.loseResponseAt("AFTER_COMMIT_RESPONSE");
    Response unconfirmed;
    long elapsedMillis;
    try {
      long started = System.nanoTime();
      unconfirmed = api.complete(tx);
      elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    } finally {
      Faults.disarm("AFTER_COMMIT_RESPONSE");
    }
    assertEquals(503, unconfirmed.status(), String.valueOf(unconfirmed.body()));
    assertEquals("SERVICE_UNAVAILABLE", unconfirmed.error());
    assertTrue(elapsedMillis <= 6000, "503 must arrive within the 5 s deadline plus tolerance, took " + elapsedMillis + " ms");

    List<CheckoutDb.Attempt> attempts = CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
    assertEquals(1, attempts.size());
    assertEquals("FINALIZED", attempts.get(0).state());
    assertEquals(1, CheckoutDb.receipts(tx), "one receipt");
    assertEquals(1, InventoryDb.saleMovements(attempts.get(0).attemptId(), SKU), "one stock decrement");
    assertEquals(8, InventoryProvisioning.position(SKU).physical());

    Response retry = api.complete(tx);
    assertEquals(409, retry.status());
    assertEquals("TRANSACTION_NOT_OPEN", retry.error());
    Reconciliation.assertPass();
  }
}
