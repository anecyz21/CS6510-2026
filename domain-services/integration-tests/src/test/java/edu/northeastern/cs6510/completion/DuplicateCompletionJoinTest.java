package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.Faults;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Quickstart V7 g: concurrent completions of one transaction join one attempt and get the same answer (A5, US2-4). */
@Tag("system")
@Tag("needs-us2")   // Toxiproxy-based until 005 task T051 re-points it at the messaging faults
class DuplicateCompletionJoinTest {
  private static final String SKU = "SKU-000080";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void fiveConcurrentCompletionsShareOneAttempt() throws Exception {
    InventoryProvisioning.setStock(SKU, 50);
    String tx = api.start("duplicate-complete").text("transactionId");
    assertEquals(200, api.scan(tx, SKU).status());
    assertEquals(200, api.scan(tx, SKU).status());

    Faults.inventoryLatency(500, 0);
    List<Response> responses = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Response>> calls = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        calls.add(pool.submit(() -> {
          go.await();
          return api.complete(tx);
        }));
      }
      go.countDown();
      for (Future<Response> call : calls) responses.add(call.get());
    } finally {
      Faults.clearToxics();
      pool.shutdownNow();
    }

    for (Response r : responses) {
      assertEquals(200, r.status(), String.valueOf(r.body()));
      assertEquals(responses.get(0).body(), r.body(), "every caller receives the same receipt");
    }
    JsonNode ops = new PublicApi(Stack.SERVICE_URLS.get("checkout")).get("/internal/v1/ops/completion-attempts?state=all&limit=1000").body();
    long attempts = 0;
    for (JsonNode a : ops.get("attempts")) if (tx.equals(a.get("transactionId").asText())) attempts++;
    assertEquals(1, attempts, "one completion attempt for the transaction");
    Reconciliation.assertPass();
  }
}
