package selfcheckout.analytics;

import selfcheckout.dataaccess.InMemorySelfCheckoutStore;

/** Deterministic hopping-window and ranking verification. */
public final class AnalyticsPipelineCheck {
    public static void main(String[] args) throws Exception {
        var store = new InMemorySelfCheckoutStore(3, 10_000);
        try (var analytics = new AnalyticsService(1_000, 500, store::findItem)) {
            require(analytics.popularItems(10).items().isEmpty(), "initial snapshot must be empty");
            for (int i = 1; i <= 1_500; i++) analytics.recordScan(i <= 750 ? "SKU-000001" : "SKU-000002");
            waitFor(analytics, 1_500);
            var result = analytics.popularItems(10);
            require(result.windowStart() == 501 && result.windowEnd() == 1_500, "expected most recent 1,000 scan bounds");
            require(result.items().size() == 2, "expected two ranked SKUs");
            require(result.items().get(0).sku().equals("SKU-000002") && result.items().get(0).scanCount() == 750,
                    "expected SKU-000002 to lead with 750 scans");
            require(result.items().get(1).sku().equals("SKU-000001") && result.items().get(1).scanCount() == 250,
                    "expected SKU-000001 to retain 250 scans");
        }
        System.out.println("Analytics pipeline deterministic check passed");
    }
    private static void waitFor(AnalyticsService analytics, long end) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (analytics.popularItems(10).windowEnd() != end && System.nanoTime() < deadline) Thread.sleep(5);
        require(analytics.popularItems(10).windowEnd() == end, "pipeline did not publish expected update");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
