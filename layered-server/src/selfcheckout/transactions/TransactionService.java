package selfcheckout.transactions;

import selfcheckout.analytics.AnalyticsService;
import selfcheckout.dataaccess.SelfCheckoutStore;
import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.LowStockAlert;
import selfcheckout.domain.Receipt;
import selfcheckout.domain.ReceiptLine;
import selfcheckout.domain.Transaction;
import selfcheckout.domain.TransactionStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransactionService {
    public static final class NotFound extends RuntimeException { public NotFound(String message) { super(message); } }
    public static final class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
    public static final class Invalid extends RuntimeException { public Invalid(String message) { super(message); } }
    public record ScanResult(String transactionId, String sku, String name, double unitPrice, int itemCount, double runningTotal) { }
    private final SelfCheckoutStore store;
    private final AnalyticsService analytics;

    public TransactionService(SelfCheckoutStore store, AnalyticsService analytics) { this.store = store; this.analytics = analytics; }
    public List<CatalogItem> catalog() { return store.catalog(); }
    public double runningTotal(Transaction transaction) { return total(transaction.scannedSkus()); }
    public Transaction start(String stationId) {
        if (stationId == null || stationId.isBlank()) throw new Invalid("stationId is required");
        return store.createTransaction(stationId);
    }
    public Transaction status(String id) {
        Transaction tx = store.findTransaction(id);
        if (tx == null) throw new NotFound("No such transaction");
        return tx;
    }
    public ScanResult scan(String id, String sku) {
        Transaction tx = status(id);
        CatalogItem item = store.findItem(sku);
        if (item == null) throw new NotFound("No such SKU");
        synchronized (tx) {
            if (tx.status() != TransactionStatus.OPEN) throw new Conflict("Transaction is not open");
            tx.scan(sku);
            analytics.recordScan(sku);
            return new ScanResult(tx.transactionId(), sku, item.name(), item.price(), tx.scannedSkus().size(), total(tx.scannedSkus()));
        }
    }
    public Receipt complete(String id) {
        Transaction tx = status(id);
        synchronized (tx) {
            if (tx.status() != TransactionStatus.OPEN) throw new Conflict("Transaction already finalized");
            List<String> basket = tx.scannedSkus();
            if (basket.isEmpty()) throw new Conflict("Cannot complete an empty transaction");
            Map<String, Integer> quantities = new LinkedHashMap<>();
            basket.forEach(sku -> quantities.merge(sku, 1, Integer::sum));
            if (!store.decrementIfAvailable(quantities)) throw new Conflict("Insufficient stock to complete transaction");
            tx.complete();
            List<ReceiptLine> lines = quantities.entrySet().stream().map(entry -> {
                CatalogItem item = store.findItem(entry.getKey());
                return new ReceiptLine(item.sku(), item.name(), item.price(), entry.getValue());
            }).toList();
            return new Receipt(tx.transactionId(), tx.stationId(), basket.size(), total(basket), tx.startedAt(), Instant.now(), lines);
        }
    }
    public List<LowStockAlert> lowStock(int threshold) { return store.lowStock(threshold); }
    private double total(List<String> skus) { return Math.round(skus.stream().map(store::findItem).mapToDouble(CatalogItem::price).sum() * 100.0) / 100.0; }
}
