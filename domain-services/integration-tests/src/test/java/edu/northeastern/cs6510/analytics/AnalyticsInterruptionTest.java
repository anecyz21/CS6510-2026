package edu.northeastern.cs6510.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.AnalyticsOps;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V8.4 (SC-010): stopping Analytics for 30 s in the middle of a 90 s checkout load never
 * fails a start, scan or completion, and every accepted scan is ingested after it returns.
 */
@Tag("system")
class AnalyticsInterruptionTest {
  private static final int WORKERS = 10;
  private static final Duration RUN = Duration.ofSeconds(90);

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void checkoutIsUnaffectedWhileAnalyticsIsDown() throws Exception {
    AnalyticsOps.Status before = AnalyticsOps.awaitDrained(Duration.ofSeconds(120));
    Map<String, Integer> failures = new ConcurrentHashMap<>();
    AtomicLong accepted = new AtomicLong();
    long endAt = System.nanoTime() + RUN.toNanos();
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    try {
      List<Future<?>> workers = new ArrayList<>();
      for (int w = 0; w < WORKERS; w++) {
        workers.add(pool.submit(() -> {
          PublicApi api = new PublicApi();
          ThreadLocalRandom random = ThreadLocalRandom.current();
          while (System.nanoTime() < endAt) {
            PublicApi.Response started = api.start("interruption");
            if (started.status() / 100 != 2) { failures.merge("start " + started.status(), 1, Integer::sum); continue; }
            String tx = started.text("transactionId");
            int scans = random.nextInt(1, 21);
            for (int i = 0; i < scans; i++) {
              int status = api.scan(tx, String.format("SKU-%06d", random.nextInt(1000, 2000))).status();
              if (status / 100 == 2) accepted.incrementAndGet();
              else failures.merge("scan " + status, 1, Integer::sum);
            }
            int completed = api.complete(tx).status();
            if (completed / 100 != 2) failures.merge("complete " + completed, 1, Integer::sum);
          }
          return null;
        }));
      }
      KnownSequenceRankingTest.sleep(30_000);
      Stack.stop("analytics");
      KnownSequenceRankingTest.sleep(30_000);
      Stack.start("analytics");
      for (Future<?> worker : workers) worker.get();
    } finally {
      pool.shutdownNow();
    }
    Stack.awaitReady("analytics", Duration.ofSeconds(120));

    assertEquals(Map.of(), failures, "no start, scan or completion failed while Analytics was down");
    assertTrue(accepted.get() > 0);
    AnalyticsOps.Status after = AnalyticsOps.awaitDrained(Duration.ofSeconds(180));
    assertEquals(accepted.get(), after.ingestedCount() - before.ingestedCount(), "every accepted scan is ingested after recovery");
  }
}
