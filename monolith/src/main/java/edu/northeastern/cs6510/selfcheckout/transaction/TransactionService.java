package edu.northeastern.cs6510.selfcheckout.transaction;


import edu.northeastern.cs6510.selfcheckout.api.ApiException;
import edu.northeastern.cs6510.selfcheckout.catalog.CatalogItem;
import edu.northeastern.cs6510.selfcheckout.catalog.CatalogService;
import edu.northeastern.cs6510.selfcheckout.inventory.InventoryStore;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import edu.northeastern.cs6510.selfcheckout.analytics.AnalyticsService;

@Service
public class TransactionService {
    private final TransactionStore transactions;
    private final CatalogService catalog;
    private final InventoryStore inventory;
    private final AnalyticsService analytics;
    public TransactionService(TransactionStore transactions, CatalogService catalog, InventoryStore inventory, AnalyticsService analytics) {
        this.transactions = transactions; this.catalog = catalog; this.inventory = inventory; this.analytics=analytics;
    }
    public Transaction start(String stationId) {
        if (stationId == null || stationId.isBlank()) throw ApiException.missingStationId();
        return transactions.createOpen(stationId.trim());
    }
    public Transaction get(String transactionId) {
        return transactions.findById(transactionId).orElseThrow(() -> ApiException.transactionNotFound(transactionId));
    }
    @Transactional
    public ScanResult scan(String transactionId, String sku) {
        Transaction transaction = transactions.findById(transactionId).orElseThrow(() -> ApiException.transactionNotFound(transactionId));
        if (transaction.status() != TransactionStatus.OPEN) throw ApiException.transactionNotOpen(transactionId);
        CatalogItem item = catalog.findBySku(sku).orElseThrow(() -> ApiException.skuNotFound(sku));
        boolean claimed = inventory.tryClaimOne(transactionId, sku);
        Transaction updated = transactions.addScan(transactionId, sku, item.priceCents(), claimed);
        analytics.recordScan(sku);
        return new ScanResult(updated, item.sku(), item.name(), item.priceCents());
    }
    @Transactional(noRollbackFor = ApiException.class)
    public Receipt complete(String transactionId) {
        Transaction transaction = transactions.findById(transactionId).orElseThrow(() -> ApiException.transactionNotFound(transactionId));
        if (transaction.status() != TransactionStatus.OPEN) throw ApiException.transactionNotOpen(transactionId);
        if (transaction.itemCount() == 0) throw ApiException.emptyBasket(transactionId);
        if (transaction.scannedQty().entrySet().stream().anyMatch(entry -> !entry.getValue().equals(transaction.claimedQty().get(entry.getKey())))) {
            cancel(transactionId);
            throw ApiException.insufficientStock(transactionId);
        }
        inventory.applyCompletion(transactionId, transaction.scannedQty(), transaction.claimedQty());
        Transaction completed = transactions.markCompleted(transactionId, Instant.now());
        List<ReceiptLine> lines = completed.scannedQty().entrySet().stream().map(entry -> {
            CatalogItem item = catalog.findBySku(entry.getKey()).orElseThrow();
            return new ReceiptLine(item.sku(), item.name(), item.priceCents(), entry.getValue());
        }).toList();
        return new Receipt(completed.transactionId(), completed.stationId(), completed.itemCount(), completed.runningTotalCents(), completed.startedAt(), completed.completedAt(), lines);
    }

    @Transactional
    public void cancel(String transactionId) {
        inventory.releaseAllClaims(transactionId);
        transactions.markCancelled(transactionId);
    }
}
