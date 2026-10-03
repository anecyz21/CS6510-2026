package selfcheckout.transactions;

import selfcheckout.analytics.AnalyticsService;
import selfcheckout.dataaccess.SelfCheckoutStore;
import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.CompletionResult;
import selfcheckout.domain.LowStockAlert;
import selfcheckout.domain.Receipt;
import selfcheckout.domain.ReceiptLine;
import selfcheckout.domain.Transaction;
import selfcheckout.domain.TransactionStatus;
import selfcheckout.domain.UnavailableItem;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransactionService {
    public static final class NotFound extends RuntimeException { public NotFound(String message) { super(message); } }
    public static final class Conflict extends RuntimeException {
        private final String error;
        public Conflict(String error, String message) { super(message); this.error = error; }
        public String error() { return error; }
    }
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
            if (tx.status() != TransactionStatus.OPEN) throw new Conflict("TRANSACTION_NOT_OPEN", "Transaction is not open");
            tx.scan(sku);
            analytics.recordScan(sku);
            return new ScanResult(tx.transactionId(), sku, item.name(), item.price(), tx.scannedSkus().size(), total(tx.scannedSkus()));
        }
    }
    public CompletionResult complete(String id) {
        Transaction tx = status(id);
        synchronized (tx) {
            if (tx.status() != TransactionStatus.OPEN) throw new Conflict("TRANSACTION_NOT_OPEN", "Transaction already finalized");
            List<String> basket = tx.scannedSkus();
            if (basket.isEmpty()) throw new Conflict("EMPTY_BASKET", "Cannot complete an empty transaction");
            Map<String, Integer> quantities = new LinkedHashMap<>();
            basket.forEach(sku -> quantities.merge(sku, 1, Integer::sum));
            SelfCheckoutStore.Fulfillment fulfillment = store.fulfillAvailable(quantities);
            tx.removeFulfilled(fulfillment.fulfilled());
            if (tx.scannedSkus().isEmpty()) tx.complete();
            List<ReceiptLine> lines = fulfillment.fulfilled().entrySet().stream().map(entry -> {
                CatalogItem item = store.findItem(entry.getKey());
                return new ReceiptLine(item.sku(), item.name(), item.price(), entry.getValue());
            }).toList();
            List<UnavailableItem> unavailableItems = fulfillment.unavailable().entrySet().stream().map(entry -> {
                CatalogItem item = store.findItem(entry.getKey());
                return new UnavailableItem(item.sku(), item.name(), entry.getValue());
            }).toList();
            int fulfilledItemCount = fulfillment.fulfilled().values().stream().mapToInt(Integer::intValue).sum();
            double fulfilledTotal = fulfillment.fulfilled().entrySet().stream()
                    .mapToDouble(entry -> store.findItem(entry.getKey()).price() * entry.getValue()).sum();
            Receipt receipt = new Receipt(tx.transactionId(), tx.stationId(), fulfilledItemCount,
                    Math.round(fulfilledTotal * 100.0) / 100.0, tx.startedAt(), Instant.now(), lines);
            return new CompletionResult(receipt, unavailableItems);
        }
    }
    public List<LowStockAlert> lowStock(int threshold) { return store.lowStock(threshold); }
    private double total(List<String> skus) { return Math.round(skus.stream().map(store::findItem).mapToDouble(CatalogItem::price).sum() * 100.0) / 100.0; }
}
