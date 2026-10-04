package selfcheckout.analytics;

import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.PopularItem;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Ordered pipeline: ingest -> window -> rank-and-publish. */
public final class AnalyticsService implements AutoCloseable {
    public record Snapshot(int windowSize, int slideInterval, long windowStart, long windowEnd,
                           Instant computedAt, List<PopularItem> items) { }
    private sealed interface Input permits ScanInput, StopInput { }
    private record ScanInput(String sku) implements Input { }
    private enum StopInput implements Input { INSTANCE }
    private sealed interface WindowMessage permits ScanEvent, StopWindow { }
    private record ScanEvent(long sequence, String sku) implements WindowMessage { }
    private enum StopWindow implements WindowMessage { INSTANCE }
    private sealed interface RankingMessage permits WindowUpdate, StopRanking { }
    private record WindowUpdate(long start, long end, List<ScanEvent> events) implements RankingMessage { }
    private enum StopRanking implements RankingMessage { INSTANCE }

    private final int windowSize, slideInterval;
    private final Function<String, CatalogItem> itemLookup;
    private final BlockingQueue<Input> ingest = new LinkedBlockingQueue<>();
    private final BlockingQueue<WindowMessage> window = new LinkedBlockingQueue<>();
    private final BlockingQueue<RankingMessage> ranking = new LinkedBlockingQueue<>();
    private final AtomicReference<Snapshot> snapshot;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Object lifecycle = new Object();
    private final Thread ingestWorker, windowWorker, rankingWorker;
    private boolean closing;

    public AnalyticsService(int windowSize, int slideInterval, Function<String, CatalogItem> itemLookup) {
        if (windowSize <= 0 || slideInterval <= 0) throw new IllegalArgumentException("window and slide must be positive");
        this.windowSize = windowSize; this.slideInterval = slideInterval; this.itemLookup = itemLookup;
        snapshot = new AtomicReference<>(new Snapshot(windowSize, slideInterval, 0, 0, Instant.now(), List.of()));
        ingestWorker = worker("analytics-ingest", this::runIngest);
        windowWorker = worker("analytics-window", this::runWindow);
        rankingWorker = worker("analytics-rank-publish", this::runRanking);
        ingestWorker.start(); windowWorker.start(); rankingWorker.start();
    }

    /** Fast, ordered submission after a transaction accepts a scan. */
    public void recordScan(String sku) {
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku is required");
        synchronized (lifecycle) {
            if (closing) throw new IllegalStateException("Analytics service is closing");
            ingest.add(new ScanInput(sku));
        }
    }

    public Snapshot popularItems(int limit) {
        Snapshot current = snapshot.get(); int bounded = Math.max(0, limit);
        return new Snapshot(current.windowSize(), current.slideInterval(), current.windowStart(), current.windowEnd(),
                current.computedAt(), current.items().subList(0, Math.min(bounded, current.items().size())));
    }

    private void runIngest() {
        long sequence = 0;
        try { while (true) { Input input = ingest.take(); if (input == StopInput.INSTANCE) { window.put(StopWindow.INSTANCE); return; }
            window.put(new ScanEvent(++sequence, ((ScanInput) input).sku())); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); fail(e); } catch (Throwable e) { fail(e); }
    }
    private void runWindow() {
        ArrayDeque<ScanEvent> recent = new ArrayDeque<>();
        try { while (true) { WindowMessage message = window.take(); if (message == StopWindow.INSTANCE) { ranking.put(StopRanking.INSTANCE); return; }
            ScanEvent event = (ScanEvent) message; recent.addLast(event); if (recent.size() > windowSize) recent.removeFirst();
            if (event.sequence() == 1 || event.sequence() % slideInterval == 0)
                ranking.put(new WindowUpdate(recent.getFirst().sequence(), event.sequence(), List.copyOf(recent))); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); fail(e); } catch (Throwable e) { fail(e); }
    }
    private void runRanking() {
        try { while (true) { RankingMessage message = ranking.take(); if (message == StopRanking.INSTANCE) return;
            WindowUpdate update = (WindowUpdate) message; Map<String, Long> counts = new HashMap<>();
            update.events().forEach(event -> counts.merge(event.sku(), 1L, Long::sum));
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
            List<PopularItem> items = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) { var entry = sorted.get(i); CatalogItem item = itemLookup.apply(entry.getKey());
                items.add(new PopularItem(entry.getKey(), item.name(), entry.getValue(), i + 1)); }
            snapshot.set(new Snapshot(windowSize, slideInterval, update.start(), update.end(), Instant.now(), List.copyOf(items))); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); fail(e); } catch (Throwable e) { fail(e); }
    }
    private static Thread worker(String name, Runnable action) { Thread thread = new Thread(action, name); thread.setDaemon(true); return thread; }
    private void fail(Throwable throwable) {
        if (failure.compareAndSet(null, throwable)) {
            // Unblock downstream stages if an upstream worker fails before it can forward its stop.
            window.offer(StopWindow.INSTANCE);
            ranking.offer(StopRanking.INSTANCE);
        }
    }
    @Override public void close() {
        synchronized (lifecycle) { if (closing) return; closing = true; ingest.add(StopInput.INSTANCE); }
        join(ingestWorker); join(windowWorker); join(rankingWorker);
        if (failure.get() != null) throw new IllegalStateException("Analytics pipeline worker failed", failure.get());
    }
    private static void join(Thread thread) { try { thread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while draining analytics", e); } }
}
