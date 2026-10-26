package edu.northeastern.cs6510.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Drives accepted scans through the gateway for the US4 tests; completions are not needed. */
public final class ScanDriver {
  private static final int SCANS_PER_TRANSACTION = 10;

  private ScanDriver() { }

  /**
   * Accepts {@code count} scans one at a time, so their acceptance order is their canonical order.
   * Scan {@code i} (from 1) is for {@code skuFor.apply(i)}. Every scan must answer 200.
   */
  public static void sequential(PublicApi api, int count, IntFunction<String> skuFor) {
    String tx = null;
    for (int i = 1; i <= count; i++) {
      if ((i - 1) % SCANS_PER_TRANSACTION == 0) tx = api.start("us4-seq").text("transactionId");
      PublicApi.Response r = api.scan(tx, skuFor.apply(i));
      assertEquals(200, r.status(), "scan " + i + " was not accepted: " + r.body());
    }
  }

  /** Statuses of every start and scan call, by status code; any 2xx counts as success (start answers 201). */
  public record Outcome(int accepted, Map<Integer, Integer> statuses) {
    public int nonOk() { return statuses.entrySet().stream().filter(e -> e.getKey() / 100 != 2).mapToInt(Map.Entry::getValue).sum(); }
  }

  /** Accepts {@code count} scans over {@code threads} workers; scan {@code i} is for {@code skuFor.apply(i)}. */
  public static Outcome parallel(int count, int threads, IntFunction<String> skuFor) throws Exception {
    AtomicInteger next = new AtomicInteger();
    AtomicInteger accepted = new AtomicInteger();
    Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        workers.add(pool.submit(() -> {
          PublicApi api = new PublicApi();
          String tx = null;
          int inTx = SCANS_PER_TRANSACTION;
          for (int i = next.incrementAndGet(); i <= count; i = next.incrementAndGet()) {
            if (inTx == SCANS_PER_TRANSACTION) {
              PublicApi.Response started = api.start("us4-par");
              statuses.merge(started.status(), 1, Integer::sum);
              if (started.status() / 100 != 2) continue;
              tx = started.text("transactionId");
              inTx = 0;
            }
            int status = api.scan(tx, skuFor.apply(i)).status();
            statuses.merge(status, 1, Integer::sum);
            if (status / 100 == 2) accepted.incrementAndGet();
            inTx++;
          }
          return null;
        }));
      }
      for (Future<?> worker : workers) worker.get();
    } finally {
      pool.shutdownNow();
    }
    return new Outcome(accepted.get(), Map.copyOf(statuses));
  }
}
