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
 * 005 quickstart Q5/Q6: with a 1 s cancel-after and Inventory paused for 0 to 1.6 s per attempt,
 * recovery's CancelRequested races the reserve reply. The cancel queues behind the reserve on the
 * attempt's partition. Each attempt must end with one unambiguous outcome: FINALIZED with a
 * COMMITTED reservation, or CANCELLED with nothing held (FR-017).
 */
@Tag("system")
class CancelCommitRaceTest {
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void shortCancelAfter() {
    Stack.requireRunning();
    Faults.requireFaultsStack();
    Stack.recreate("checkout", Map.of("CHECKOUT_RECOVERY_CANCEL_AFTER", "1s"));
  }

  @AfterAll
  static void restoreCheckout() {
    Stack.recreate("checkout", Map.of("CHECKOUT_RECOVERY_CANCEL_AFTER", "30s"));
  }

  @Test
  void cancelAndCommitNeverBothTakeEffect() throws Exception {
    Map<String, Integer> outcomes = new HashMap<>();
    {
      for (int i = 0; i < 20; i++) {
        String sku = String.format("SKU-%06d", 200 + i);
        InventoryProvisioning.setStock(sku, 5);
        String tx = api.start("race-" + i).text("transactionId");
        assertEquals(200, api.scan(tx, sku).status());
        int status;
        try (AutoCloseable paused = Faults.pauseFor("inventory", Duration.ofMillis((i % 5) * 400L))) {
          status = api.complete(tx).status();
        }
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
    }
    System.out.println("cancel/commit race outcomes: " + outcomes);
    Reconciliation.assertPass();
  }
}
