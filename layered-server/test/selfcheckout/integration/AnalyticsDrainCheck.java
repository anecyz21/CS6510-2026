package selfcheckout.integration;

import selfcheckout.analytics.AnalyticsService;
import selfcheckout.dataaccess.InMemorySelfCheckoutStore;

/** Confirms close drains accepted scans through the final eligible window update. */
public final class AnalyticsDrainCheck {
    public static void main(String[] args) {
        var store = new InMemorySelfCheckoutStore(2, 10_000);
        var analytics = new AnalyticsService(100, 50, store::findItem);
        for (int i = 0; i < 500; i++) analytics.recordScan("SKU-000001");
        analytics.close();
        var result = analytics.popularItems(10);
        if (result.windowEnd() != 500 || result.items().get(0).scanCount() != 100) throw new AssertionError("close did not drain accepted scans");
        System.out.println("Analytics drain check passed");
    }
}
