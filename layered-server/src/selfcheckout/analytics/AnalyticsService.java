package selfcheckout.analytics;

import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.PopularItem;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class AnalyticsService {
    public record Snapshot(int windowSize, int slideInterval, long windowStart, long windowEnd,
                           Instant computedAt, List<PopularItem> items) { }
    private final int windowSize;
    private final int slideInterval;
    private final Function<String, CatalogItem> itemLookup;
    private final ArrayDeque<String> recentScans = new ArrayDeque<>();
    private long sequence;
    private Snapshot snapshot;

    public AnalyticsService(int windowSize, int slideInterval, Function<String, CatalogItem> itemLookup) {
        this.windowSize = windowSize;
        this.slideInterval = slideInterval;
        this.itemLookup = itemLookup;
        this.snapshot = new Snapshot(windowSize, slideInterval, 0, 0, Instant.now(), List.of());
    }
    public synchronized void recordScan(String sku) {
        sequence++;
        recentScans.addLast(sku);
        while (recentScans.size() > windowSize) recentScans.removeFirst();
        if (sequence == 1 || sequence % slideInterval == 0) recompute();
    }
    public synchronized Snapshot popularItems(int limit) {
        int boundedLimit = Math.max(0, limit);
        return new Snapshot(snapshot.windowSize(), snapshot.slideInterval(), snapshot.windowStart(), snapshot.windowEnd(),
                snapshot.computedAt(), snapshot.items().subList(0, Math.min(boundedLimit, snapshot.items().size())));
    }
    private void recompute() {
        Map<String, Long> counts = new HashMap<>();
        recentScans.forEach(sku -> counts.merge(sku, 1L, Long::sum));
        List<Map.Entry<String, Long>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        List<PopularItem> items = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            var entry = ranked.get(i); CatalogItem item = itemLookup.apply(entry.getKey());
            items.add(new PopularItem(entry.getKey(), item.name(), entry.getValue(), i + 1));
        }
        long start = Math.max(1, sequence - recentScans.size() + 1);
        snapshot = new Snapshot(windowSize, slideInterval, start, sequence, Instant.now(), List.copyOf(items));
    }
}
