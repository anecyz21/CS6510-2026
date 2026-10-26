package edu.northeastern.cs6510.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.AnalyticsOps;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.ScanDriver;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V8.5–6 (US4-5, US4-6): an empty store answers an empty ranking, and readers polling
 * while windows are published only ever see one complete published window.
 */
@Tag("system")
class PopularItemsViewTest {
  private static final int READERS = 8;

  @BeforeAll
  static void freshStack() {
    Stack.requireRunning();
    Stack.reset();
  }

  @Test
  void readersOnlyEverSeeOneCompletePublishedWindow() throws Exception {
    PublicApi.Response empty = new PublicApi().popularItems(null);
    assertEquals(200, empty.status());
    assertEquals(0, empty.body().get("items").size());
    assertEquals(0, empty.body().get("windowStart").asLong());
    assertEquals(0, empty.body().get("windowEnd").asLong());

    Map<Long, List<String>> seen = new ConcurrentHashMap<>();
    List<String> violations = new CopyOnWriteArrayList<>();
    AtomicBoolean writing = new AtomicBoolean(true);
    ExecutorService pool = Executors.newFixedThreadPool(READERS);
    long responses;
    try {
      List<Future<Long>> readers = new ArrayList<>();
      for (int r = 0; r < READERS; r++) {
        readers.add(pool.submit(() -> {
          PublicApi api = new PublicApi();
          long count = 0;
          while (writing.get()) {
            PublicApi.Response response = api.popularItems(2000);
            count++;
            if (response.status() != 200) { violations.add("status " + response.status()); continue; }
            check(response.body(), seen, violations);
          }
          return count;
        }));
      }
      ScanDriver.Outcome outcome = ScanDriver.parallel(4000, 8, i -> String.format("SKU-%06d", 1 + (i * 7919 % 40)));
      assertEquals(0, outcome.nonOk(), outcome.statuses().toString());
      AnalyticsOps.awaitDrained(Duration.ofSeconds(120));
      KnownSequenceRankingTest.sleep(1000);
      writing.set(false);
      responses = 0;
      for (Future<Long> reader : readers) responses += reader.get();
    } finally {
      writing.set(false);
      pool.shutdownNow();
    }

    assertEquals(List.of(), violations);
    assertTrue(seen.containsKey(4000L), "the last window was observed: " + seen.keySet());
    assertTrue(responses > seen.size());
    for (Map.Entry<Long, List<String>> window : seen.entrySet()) {
      if (window.getKey() == 0) continue;
      List<String> stored = AnalyticsOps.snapshotItems(window.getKey()).stream()
          .map(i -> i.sku() + "|" + i.name() + "|" + i.scanCount() + "|" + i.rank()).toList();
      assertEquals(stored, window.getValue(), "response for window " + window.getKey() + " equals the stored snapshot");
    }
  }

  /** One window per response: counts fit inside it and match every other response for that window. */
  private static void check(JsonNode body, Map<Long, List<String>> seen, List<String> violations) {
    long start = body.get("windowStart").asLong();
    long end = body.get("windowEnd").asLong();
    long sum = 0;
    List<String> items = new ArrayList<>();
    for (JsonNode item : body.get("items")) {
      sum += item.get("scanCount").asLong();
      items.add(item.get("sku").asText() + "|" + item.get("name").asText() + "|" + item.get("scanCount").asLong() + "|" + item.get("rank").asInt());
    }
    if (end > 0 && sum > end - start + 1) violations.add("window " + end + " counts " + sum + " scans in " + (end - start + 1));
    List<String> first = seen.putIfAbsent(end, items);
    if (first != null && !first.equals(items)) violations.add("window " + end + " answered two different rankings");
  }
}
