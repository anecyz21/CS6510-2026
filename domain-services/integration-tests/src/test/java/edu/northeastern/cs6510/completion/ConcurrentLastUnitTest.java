package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Quickstart V6: concurrent completions never oversell, and other units still sell (US1-5, SC-003). */
@Tag("system")
class ConcurrentLastUnitTest {
  private static final String CONTESTED = "SKU-000010";
  private static final String PLENTIFUL = "SKU-000011";
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void fiftyStationsCompeteForTheLastUnit() throws Exception {
    InventoryProvisioning.setStock(CONTESTED, 1);
    InventoryProvisioning.setStock(PLENTIFUL, 1000);
    List<String> transactions = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      String tx = api.start("last-unit-" + i).text("transactionId");
      assertEquals(200, api.scan(tx, CONTESTED).status());
      assertEquals(200, api.scan(tx, PLENTIFUL).status());
      transactions.add(tx);
    }

    List<Response> receipts = completeAllAtOnce(transactions);

    int withContested = 0;
    for (int i = 0; i < transactions.size(); i++) {
      Response receipt = receipts.get(i);
      assertEquals(200, receipt.status(), String.valueOf(receipt.body()));
      Map<String, Integer> bought = quantities(receipt.body());
      assertEquals(1, bought.get(PLENTIFUL), "every station still buys the plentiful SKU");
      JsonNode tx = api.transaction(transactions.get(i)).body();
      if (bought.containsKey(CONTESTED)) {
        withContested++;
        assertEquals("COMPLETED", tx.get("status").asText());
      } else {
        assertEquals("OPEN", tx.get("status").asText());
        assertEquals(1, tx.get("itemCount").asInt(), "the contested unit stays in the basket");
      }
    }
    assertEquals(1, withContested, "exactly one receipt contains the last unit");
    assertEquals(0, InventoryProvisioning.position(CONTESTED).physical());
    Reconciliation.assertPass();
  }

  @Test
  void twoHundredParallelCompletionsAgainstScarceStock() throws Exception {
    Random random = new Random(42);
    Map<String, Integer> stock = new HashMap<>();
    for (int i = 100; i < 120; i++) {
      String sku = String.format("SKU-%06d", i);
      stock.put(sku, random.nextInt(4));
      InventoryProvisioning.setStock(sku, stock.get(sku));
    }
    List<String> skus = new ArrayList<>(stock.keySet());
    Map<String, Integer> demand = new HashMap<>();
    List<String> transactions = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      String tx = api.start("scarce-" + i).text("transactionId");
      int units = 1 + random.nextInt(3);
      for (int u = 0; u < units; u++) {
        String sku = skus.get(random.nextInt(skus.size()));
        assertEquals(200, api.scan(tx, sku).status());
        demand.merge(sku, 1, Integer::sum);
      }
      transactions.add(tx);
    }

    List<Response> results = completeAllAtOnce(transactions);

    Map<String, Integer> purchased = new HashMap<>();
    for (Response r : results) {
      assertTrue(r.status() == 200 || (r.status() == 409 && "INSUFFICIENT_STOCK".equals(r.error())),
          "unexpected completion outcome " + r.status() + " " + r.body());
      if (r.status() == 200) quantities(r.body()).forEach((sku, q) -> purchased.merge(sku, q, Integer::sum));
    }
    for (String sku : skus) {
      int expected = Math.min(stock.get(sku), demand.getOrDefault(sku, 0));
      assertEquals(expected, purchased.getOrDefault(sku, 0), sku + ": sold exactly min(stock, demand)");
      assertEquals(stock.get(sku) - expected, InventoryProvisioning.position(sku).physical(), sku + " physical stock");
    }
    Reconciliation.assertPass();
  }

  private List<Response> completeAllAtOnce(List<String> transactions) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(transactions.size());
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Response>> futures = new ArrayList<>();
      for (String tx : transactions) {
        Callable<Response> call = () -> {
          go.await();
          return api.complete(tx);
        };
        futures.add(pool.submit(call));
      }
      go.countDown();
      List<Response> results = new ArrayList<>();
      for (Future<Response> f : futures) results.add(f.get());
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static Map<String, Integer> quantities(JsonNode receipt) {
    Map<String, Integer> q = new HashMap<>();
    receipt.get("lines").forEach(l -> q.put(l.get("sku").asText(), l.get("quantity").asInt()));
    return q;
  }
}
