package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.InventoryDb;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 005 quickstart Q5 (Inventory down 20 s) and US2 scenarios 1–2. While Inventory is down, the
 * request answers 503 within the deadline. Inventory's reply arrives after the request has
 * returned, and the attempt finalizes once in the background. A repeated completion of the
 * now-empty basket is TRANSACTION_NOT_OPEN, with one receipt and one stock decrement.
 */
@Tag("system")
class LateReplyTest {
  private static final String SKU = "SKU-000065";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void aReplyAfterTheDeadlineStillFinalizesOnce() throws Exception {
    InventoryProvisioning.setStock(SKU, 10);
    String tx = api.start("late-reply").text("transactionId");
    assertEquals(200, api.scan(tx, SKU).status());
    assertEquals(200, api.scan(tx, SKU).status());

    Instant stopped = Instant.now();
    Stack.stop("inventory");
    try {
      long started = System.nanoTime();
      Response unconfirmed = api.complete(tx);
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
      assertEquals(503, unconfirmed.status(), String.valueOf(unconfirmed.body()));
      assertTrue(elapsedMillis <= 6000, "503 took " + elapsedMillis + " ms");
      long remaining = Duration.ofSeconds(20).minus(Duration.between(stopped, Instant.now())).toMillis();
      if (remaining > 0) Thread.sleep(remaining);
    } finally {
      Stack.start("inventory");
    }

    List<CheckoutDb.Attempt> attempts = CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
    assertEquals(1, attempts.size());
    assertEquals("FINALIZED", attempts.get(0).state());
    assertEquals(1, CheckoutDb.receipts(tx));
    InventoryDb.awaitSalesApplied();
    assertEquals(1, InventoryDb.saleMovements(attempts.get(0).attemptId(), SKU));
    assertEquals(8, InventoryProvisioning.position(SKU).physical());

    Response retry = api.complete(tx);
    assertEquals(409, retry.status());
    assertEquals("TRANSACTION_NOT_OPEN", retry.error());
    Reconciliation.assertPass();
  }
}
