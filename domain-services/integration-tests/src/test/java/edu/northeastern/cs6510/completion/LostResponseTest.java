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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 005 quickstart Q6 and FR-028 (Inventory crash between its commit and its reply): Inventory
 * applies the reserve, but with {@code MESSAGING_FAULTS_HOLD_SENDS=true} its committed replies stay
 * unsent in {@code reply_outbox}, as a crash right after the reservation commit would leave them.
 * The request answers 503 within the deadline. After Inventory restarts normally, the sweep sends
 * each held reply once, the attempt settles once, and a retry never resells (US2-1, US2-2).
 */
@Tag("system")
class LostResponseTest {
  private static final String SKU = "SKU-000060";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() {
    Stack.requireRunning();
    Faults.requireFaultsStack();
  }

  @AfterEach
  void restore() {
    Stack.recreate("inventory", Map.of("MESSAGING_FAULTS_HOLD_SENDS", "false"));
    Stack.recreate("checkout", Map.of("CHECKOUT_RECOVERY_CANCEL_AFTER", "30s"));
  }

  @Test
  void heldRepliesAreSentOnceAfterRestartAndTheAttemptFinalizesOnce() {
    // Inventory's restart below takes longer than the default 30 s cancel-after; this test is about
    // the reply surviving, so cancellation is pushed out of the way.
    Stack.recreate("checkout", Map.of("CHECKOUT_RECOVERY_CANCEL_AFTER", "300s"));
    Stack.recreate("inventory", Map.of("MESSAGING_FAULTS_HOLD_SENDS", "true"));
    InventoryProvisioning.setStock(SKU, 10);
    String tx = api.start("lost-reply").text("transactionId");
    assertEquals(200, api.scan(tx, SKU).status());
    assertEquals(200, api.scan(tx, SKU).status());

    long started = System.nanoTime();
    Response unconfirmed = api.complete(tx);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertEquals(503, unconfirmed.status(), String.valueOf(unconfirmed.body()));
    assertEquals("SERVICE_UNAVAILABLE", unconfirmed.error());
    assertTrue(elapsedMillis <= 6000, "503 must arrive within the 5 s deadline plus tolerance, took " + elapsedMillis + " ms");
    String attemptId = CheckoutDb.awaitActiveAttempt(tx, Duration.ofSeconds(5));
    assertEquals("RESERVED", InventoryDb.reservationState(attemptId), "Inventory committed the reserve; only its reply is held");

    Stack.recreate("inventory", Map.of("MESSAGING_FAULTS_HOLD_SENDS", "false"));

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
