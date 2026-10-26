package edu.northeastern.cs6510.analytics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.AnalyticsOps;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.ScanDriver;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V8.1 (SC-006): 1,500 scans in a known order give exactly the window [501, 1500] with
 * counts and ranks computed here, after earlier publications at 1, 500 and 1000.
 */
@Tag("system")
class KnownSequenceRankingTest {
  /** Uneven counts with ties: scan i is for SKU-00000(1 + (i*i mod 7)). */
  static String skuFor(int i) { return String.format("SKU-%06d", 1 + (int) ((long) i * i % 7)); }

  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void freshStack() {
    Stack.requireRunning();
    Stack.reset();
  }

  @Test
  void windowEndingAt1500HoldsScans501To1500RankedByCountThenSku() {
    ScanDriver.sequential(api, 1500, KnownSequenceRankingTest::skuFor);
    JsonNode body = awaitWindowEnd(1500);

    assertEquals(1000, body.get("windowSize").asInt());
    assertEquals(500, body.get("slideInterval").asInt());
    assertEquals(501, body.get("windowStart").asLong());
    List<String[]> expected = expectedRanking(501, 1500);
    Map<String, String> names = catalogNames();
    JsonNode items = body.get("items");
    assertEquals(expected.size(), items.size());
    for (int i = 0; i < expected.size(); i++) {
      JsonNode item = items.get(i);
      assertEquals(expected.get(i)[0], item.get("sku").asText(), "rank " + (i + 1));
      assertEquals(Long.parseLong(expected.get(i)[1]), item.get("scanCount").asLong(), item.toString());
      assertEquals(i + 1, item.get("rank").asInt());
      assertEquals(names.get(item.get("sku").asText()), item.get("name").asText(), "the catalog name, never the SKU");
      assertNotEquals(item.get("sku").asText(), item.get("name").asText());
    }

    List<long[]> windows = AnalyticsOps.snapshotWindows();
    assertEquals(4, windows.size(), "publications at 1, 500, 1000 and 1500");
    assertArrayEquals(new long[] {1, 1}, windows.get(0));
    assertArrayEquals(new long[] {1, 500}, windows.get(1));
    assertArrayEquals(new long[] {1, 1000}, windows.get(2));
    assertArrayEquals(new long[] {501, 1500}, windows.get(3));
    assertEquals(List.of(new AnalyticsOps.SnapshotItem(1, skuFor(1), names.get(skuFor(1)), 1, 1)), AnalyticsOps.snapshotItems(1));
    List<String[]> at500 = expectedRanking(1, 500);
    List<AnalyticsOps.SnapshotItem> stored500 = AnalyticsOps.snapshotItems(500);
    assertEquals(at500.size(), stored500.size());
    for (int i = 0; i < at500.size(); i++) {
      assertEquals(at500.get(i)[0], stored500.get(i).sku());
      assertEquals(Long.parseLong(at500.get(i)[1]), stored500.get(i).scanCount());
    }
  }

  /** [sku, count] for scans first..last, by count descending then SKU ascending. */
  static List<String[]> expectedRanking(int first, int last) {
    Map<String, Long> counts = new TreeMap<>();
    for (int i = first; i <= last; i++) counts.merge(skuFor(i), 1L, Long::sum);
    List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
    entries.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
    List<String[]> out = new ArrayList<>();
    for (Map.Entry<String, Long> e : entries) out.add(new String[] {e.getKey(), String.valueOf(e.getValue())});
    return out;
  }

  private Map<String, String> catalogNames() {
    Map<String, String> names = new HashMap<>();
    for (JsonNode item : api.items().body().get("items")) names.put(item.get("sku").asText(), item.get("name").asText());
    return names;
  }

  private JsonNode awaitWindowEnd(long end) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
    JsonNode last = null;
    while (Instant.now().isBefore(deadline)) {
      PublicApi.Response r = api.popularItems(2000);
      last = r.body();
      if (r.status() == 200 && last.get("windowEnd").asLong() == end) return last;
      sleep(500);
    }
    fail("window " + end + " was not published: " + last);
    return null;
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
