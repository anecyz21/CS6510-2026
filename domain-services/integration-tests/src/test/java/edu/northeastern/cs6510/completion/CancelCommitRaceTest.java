package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.Faults;
import edu.northeastern.cs6510.support.InventoryDb;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V7 h: with a 1 s cancel-after and slow Inventory responses, recovery's cancellation
 * races the request's reserve and commit. Each attempt must end with one unambiguous outcome:
 * FINALIZED with a COMMITTED reservation, or CANCELLED with nothing committed (FR-017).
 */
@Tag("system")
@Tag("needs-us2")   // Toxiproxy-based until 005 task T051 re-points it at the messaging faults
class CancelCommitRaceTest {
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void shortCancelAfter() {
    Stack.requireRunning();
    Stack.recreate("checkout", Map.of("CHECKOUT_RECOVERY_CANCEL_AFTER", "1s"));
  }

  @AfterAll
  static void restoreCheckout() {
    Faults.clearToxics();
    Stack.recreate("checkout", Map.of("CHECKOUT_RECOVERY_CANCEL_AFTER", "30s"));
  }

  @Test
  void cancelAndCommitNeverBothTakeEffect() {
    Map<String, Integer> outcomes = new HashMap<>();
    Faults.inventoryLatency(1300, 400);
    try {
      for (int i = 0; i < 20; i++) {
        String sku = String.format("SKU-%06d", 200 + i);
        InventoryProvisioning.setStock(sku, 5);
        String tx = api.start("race-" + i).text("transactionId");
        assertEquals(200, api.scan(tx, sku).status());
        int status = api.complete(tx).status();
        assertTrue(status == 200 || status == 503, "unexpected completion status " + status);

        List<CheckoutDb.Attempt> attempts = CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
        for (CheckoutDb.Attempt attempt : attempts) {
          String reservation = InventoryDb.reservationState(attempt.attemptId());
          switch (attempt.state()) {
            case "FINALIZED" -> assertEquals("COMMITTED", reservation, attempt + " finalized without a committed reservation");
            case "CANCELLED" -> assertTrue(reservation == null || Set.of("RELEASED", "CANCELLED").contains(reservation),
                attempt + " cancelled but its reservation is " + reservation);
            default -> throw new AssertionError("unexpected terminal state " + attempt);
          }
          outcomes.merge(attempt.state(), 1, Integer::sum);
        }
        int reservedNow = InventoryProvisioning.position(sku).reserved();
        assertEquals(0, reservedNow, sku + " still has reserved stock");
      }
    } finally {
      Faults.clearToxics();
    }
    System.out.println("cancel/commit race outcomes: " + outcomes);
    Reconciliation.assertPass();
  }
}
