package edu.northeastern.cs6510.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A small shopper workload for the 005 messaging tests: each loop starts a transaction, scans
 * {@code scansPerTransaction} units of rotating SKUs and completes it, through the gateway. Every
 * public status is counted per operation, so a test can assert which outcomes occurred.
 */
public final class Workload implements AutoCloseable {
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Thread[] workers;

  private Workload(int threads, int scansPerTransaction, String station) {
    workers = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      int worker = t;
      workers[t] = new Thread(() -> {
        PublicApi api = new PublicApi();
        int n = 0;
        while (running.get()) {
          PublicApi.Response started = api.start(station + "-" + worker);
          count("START", started.status());
          if (started.status() / 100 != 2) continue;
          String tx = started.text("transactionId");
          for (int i = 0; i < scansPerTransaction; i++) {
            count("SCAN", api.scan(tx, String.format("SKU-%06d", 300 + (worker * 7 + n++) % 50)).status());
          }
          count("COMPLETE", api.complete(tx).status());
        }
      }, "workload-" + t);
      workers[t].setDaemon(true);
      workers[t].start();
    }
  }

  public static Workload start(int threads, int scansPerTransaction, String station) {
    return new Workload(threads, scansPerTransaction, station);
  }

  private void count(String operation, int status) { statuses.merge(operation + " " + status, 1, Integer::sum); }

  /** Counts so far, e.g. {"SCAN 200": 412, "COMPLETE 503": 3}. */
  public Map<String, Integer> statuses() { return Map.copyOf(statuses); }

  public int count(String operationAndStatus) { return statuses.getOrDefault(operationAndStatus, 0); }

  @Override
  public void close() throws InterruptedException {
    running.set(false);
    for (Thread worker : workers) worker.join(30_000);
  }
}
