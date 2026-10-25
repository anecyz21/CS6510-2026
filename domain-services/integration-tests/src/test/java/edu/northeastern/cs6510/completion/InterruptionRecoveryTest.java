package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.Faults;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Quickstart V7 a–d: Checkout crashes at each saga boundary, restarts, and recovery produces
 * exactly one purchase or one released attempt (US2-1, US2-2, SC-004). Needs the faults stack.
 */
@Tag("system")
class InterruptionRecoveryTest {
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @ParameterizedTest(name = "halt {0}")
  @CsvSource({"AFTER_FREEZE, SKU-000041, true", "AFTER_RESERVE_RESPONSE, SKU-000042, true",
              "AFTER_PREPARE, SKU-000043, false", "AFTER_COMMIT_RESPONSE, SKU-000044, false"})
  void recoveryResolvesAnAttemptInterruptedAt(String point, String sku, boolean mayCancel) {
    InventoryProvisioning.setStock(sku, 10);
    String tx = api.start("halt-" + point).text("transactionId");
    assertEquals(200, api.scan(tx, sku).status());
    assertEquals(200, api.scan(tx, sku).status());

    Faults.haltAt(point);
    try {
      api.complete(tx);   // the JVM halts mid-request; the gateway answers 503 or the connection drops
    } catch (IllegalStateException connectionLost) {
      // expected for a crash
    }
    // The request can return (gateway 503) before the container has finished exiting.
    Stack.awaitExited("checkout", Duration.ofSeconds(30));
    Stack.start("checkout");
    Stack.awaitReady("checkout", Duration.ofSeconds(120));

    List<CheckoutDb.Attempt> attempts = CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
    assertEquals(1, attempts.size(), "never a second, competing attempt for one frozen basket");
    InventoryProvisioning.Position position = InventoryProvisioning.position(sku);
    if ("FINALIZED".equals(attempts.get(0).state())) {
      assertEquals(1, CheckoutDb.receipts(tx));
      assertEquals(8, position.physical(), "stock decremented exactly once");
    } else {
      assertTrue(mayCancel, point + " is past the durable receipt intent and must only move forward");
      assertEquals("CANCELLED", attempts.get(0).state());
      assertEquals(10, position.physical());
    }
    assertEquals(0, position.reserved());
    Reconciliation.assertPass();
  }
}
