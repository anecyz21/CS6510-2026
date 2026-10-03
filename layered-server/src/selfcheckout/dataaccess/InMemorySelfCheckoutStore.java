package selfcheckout.dataaccess;

import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.LowStockAlert;
import selfcheckout.domain.Transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemorySelfCheckoutStore implements SelfCheckoutStore {
    private final List<CatalogItem> catalog = new ArrayList<>();
    private final Map<String, CatalogItem> items = new ConcurrentHashMap<>();
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final AtomicLong transactionSequence = new AtomicLong();
    private final Object inventoryLock = new Object();

    public InMemorySelfCheckoutStore(int catalogSize, int stockPerItem) {
        if (catalogSize < 1 || stockPerItem < 0) throw new IllegalArgumentException("Invalid startup inventory");
        for (int i = 1; i <= catalogSize; i++) {
            String sku = "SKU-" + String.format("%06d", i);
            double price = Math.round((0.5 + (i % 47) * 0.35) * 100.0) / 100.0;
            CatalogItem item = new CatalogItem(sku, "Item " + i, price);
            catalog.add(item);
            items.put(sku, item);
            stock.put(sku, stockPerItem);
        }
    }

    public List<CatalogItem> catalog() { return List.copyOf(catalog); }
    public CatalogItem findItem(String sku) { return items.get(sku); }
    public Transaction createTransaction(String stationId) {
        String id = "tx-" + transactionSequence.incrementAndGet();
        Transaction transaction = new Transaction(id, stationId);
        transactions.put(id, transaction);
        return transaction;
    }
    public Transaction findTransaction(String id) { return transactions.get(id); }
    public Fulfillment fulfillAvailable(Map<String, Integer> quantities) {
        synchronized (inventoryLock) {
            Map<String, Integer> fulfilled = new LinkedHashMap<>();
            Map<String, Integer> unavailable = new LinkedHashMap<>();
            for (var entry : quantities.entrySet()) {
                int requested = entry.getValue();
                int available = stock.getOrDefault(entry.getKey(), 0);
                int sold = Math.min(requested, available);
                if (sold > 0) fulfilled.put(entry.getKey(), sold);
                if (sold < requested) unavailable.put(entry.getKey(), requested - sold);
            }
            fulfilled.forEach((sku, count) -> stock.computeIfPresent(sku, (ignored, current) -> current - count));
            return new Fulfillment(fulfilled, unavailable);
        }
    }
    public List<LowStockAlert> lowStock(int threshold) {
        List<LowStockAlert> alerts = new ArrayList<>();
        for (CatalogItem item : catalog) {
            int quantity = stock.get(item.sku());
            if (quantity < threshold) alerts.add(new LowStockAlert(item.sku(), item.name(), quantity, threshold, Instant.now()));
        }
        return alerts;
    }
}
