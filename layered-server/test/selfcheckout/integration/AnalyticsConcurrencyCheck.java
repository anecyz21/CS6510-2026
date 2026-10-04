package selfcheckout.integration;

import selfcheckout.analytics.AnalyticsService;
import selfcheckout.dataaccess.InMemorySelfCheckoutStore;
import java.util.concurrent.Executors;

/** Confirms readers observe only complete, internally consistent snapshots during ingestion. */
public final class AnalyticsConcurrencyCheck {
    public static void main(String[] args) throws Exception {
        var store = new InMemorySelfCheckoutStore(3, 10_000);
        try (var analytics = new AnalyticsService(100, 25, store::findItem);
             var workers = Executors.newFixedThreadPool(4)) {
            var writer = workers.submit(() -> { for (int i = 0; i < 1_000; i++) analytics.recordScan("SKU-00000" + ((i % 3) + 1)); });
            var reader = workers.submit(() -> { while (!writer.isDone()) {
                var snapshot = analytics.popularItems(10);
                long total = snapshot.items().stream().mapToLong(item -> item.scanCount()).sum();
                if (total > snapshot.windowSize() || snapshot.windowEnd() < snapshot.windowStart() - 1) throw new AssertionError("partial snapshot observed");
            }});
            writer.get(); reader.get();
        }
        System.out.println("Analytics concurrency check passed");
    }
}
