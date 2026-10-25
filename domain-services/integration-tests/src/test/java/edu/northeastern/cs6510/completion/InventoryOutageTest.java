package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.CheckoutDb;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V7 f: Inventory is down for 45 s. Every completion answers 503 within its deadline,
 * the attempts stay visible to operators, and all of them resolve after Inventory returns (US2-6).
 */
@Tag("system")
class InventoryOutageTest {
  private static final String SKU = "SKU-000070";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void completionsDuringAnInventoryOutageAnswer503AndResolveLater() throws Exception {
    InventoryProvisioning.setStock(SKU, 100);
    List<String> transactions = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String tx = api.start("outage-" + i).text("transactionId");
      assertEquals(200, api.scan(tx, SKU).status());
      transactions.add(tx);
    }

    Instant stopped = Instant.now();
    Stack.stop("inventory");
    ExecutorService pool = Executors.newFixedThreadPool(transactions.size());
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<long[]>> calls = new ArrayList<>();
      for (String tx : transactions) {
        calls.add(pool.submit(() -> {
          go.await();
          long started = System.nanoTime();
          Response r = api.complete(tx);
          return new long[] {r.status(), (System.nanoTime() - started) / 1_000_000};
        }));
      }
      go.countDown();
      for (Future<long[]> call : calls) {
        long[] result = call.get();
        assertEquals(503, result[0]);
        assertTrue(result[1] <= 6000, "503 took " + result[1] + " ms");
      }

      Set<String> listed = new HashSet<>();
      JsonNode ops = new PublicApi(Stack.SERVICE_URLS.get("checkout")).get("/internal/v1/ops/completion-attempts?state=unresolved&limit=1000").body();
      ops.get("attempts").forEach(a -> listed.add(a.get("transactionId").asText()));
      assertTrue(listed.containsAll(transactions), "every unresolved attempt is visible to operators");

      long remaining = Duration.ofSeconds(45).minus(Duration.between(stopped, Instant.now())).toMillis();
      if (remaining > 0) Thread.sleep(remaining);
    } finally {
      Stack.start("inventory");
      pool.shutdownNow();
    }
    Stack.awaitReady("inventory", Duration.ofSeconds(120));

    for (String tx : transactions) CheckoutDb.awaitResolved(tx, Duration.ofSeconds(60));
    Reconciliation.assertPass();
  }
}
