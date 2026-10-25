package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Quickstart V5 step 6: a scan during an unresolved completion is rejected at once and changes nothing (US2-4, SC-001). */
@Tag("system")
class BusyScanTest {
  private static final String SKU = "SKU-000030";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void scanDuringAnUnresolvedCompletionIsBusy() throws Exception {
    InventoryProvisioning.setStock(SKU, 5);
    String tx = api.start("busy-scan").text("transactionId");
    assertEquals(200, api.scan(tx, SKU).status());
    int units = CheckoutDb.basketUnits(tx);
    int events = CheckoutDb.acceptedScanEvents(tx);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    Stack.pause("inventory");
    try {
      Future<Response> completion = pool.submit(() -> api.complete(tx));
      CheckoutDb.awaitActiveAttempt(tx, Duration.ofSeconds(5));

      long started = System.nanoTime();
      Response busy = api.scan(tx, SKU);
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
      assertEquals(409, busy.status(), String.valueOf(busy.body()));
      assertEquals("TRANSACTION_BUSY", busy.error());
      assertTrue(elapsedMillis < 1000, "busy scan answered in " + elapsedMillis + " ms; it must not wait");
      assertEquals(units, CheckoutDb.basketUnits(tx), "a busy scan changes no basket line");
      assertEquals(events, CheckoutDb.acceptedScanEvents(tx), "a busy scan records no accepted scan");

      Response unconfirmed = completion.get();
      assertEquals(503, unconfirmed.status(), "Inventory is paused, so the purchase cannot be confirmed");
      assertEquals("SERVICE_UNAVAILABLE", unconfirmed.error());
    } finally {
      Stack.unpause("inventory");
      pool.shutdownNow();
    }

    CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
    Reconciliation.assertPass();
  }
}
