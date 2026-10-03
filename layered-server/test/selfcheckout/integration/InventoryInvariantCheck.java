package selfcheckout.integration;

import selfcheckout.analytics.AnalyticsService;
import selfcheckout.dataaccess.InMemorySelfCheckoutStore;
import selfcheckout.domain.CompletionResult;
import selfcheckout.domain.Transaction;
import selfcheckout.domain.TransactionStatus;
import selfcheckout.transactions.TransactionService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Concurrent transaction-level check for fulfilled and retained inventory accounting. */
public final class InventoryInvariantCheck {
    public static void main(String[] args) throws Exception {
        int initialStock = 120;
        var store = new InMemorySelfCheckoutStore(3, initialStock);
        var transactions = new TransactionService(store, new AnalyticsService(1_000, 500, store::findItem));
        List<List<String>> baskets = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            baskets.add(index % 2 == 0
                    ? List.of("SKU-000001", "SKU-000001", "SKU-000002", "SKU-000003")
                    : List.of("SKU-000001", "SKU-000002", "SKU-000003", "SKU-000003"));
        }
        try (var executor = Executors.newFixedThreadPool(32)) {
            List<Callable<Attempt>> attempts = java.util.stream.IntStream.range(0, baskets.size())
                    .<Callable<Attempt>>mapToObj(index -> () -> complete(transactions, "station-" + index, baskets.get(index)))
                    .toList();
            List<Attempt> completed = new ArrayList<>();
            for (var future : executor.invokeAll(attempts)) completed.add(future.get());
            Map<String, Integer> fulfilled = new HashMap<>();
            for (Attempt attempt : completed) {
                Map<String, Integer> requested = quantities(attempt.basket());
                Map<String, Integer> sold = new HashMap<>();
                attempt.result().receipt().lines().forEach(line -> sold.put(line.sku(), line.quantity()));
                Map<String, Integer> retained = new HashMap<>();
                attempt.result().unavailableItems().forEach(item -> retained.put(item.sku(), item.quantity()));
                for (String sku : requested.keySet()) {
                    if (sold.getOrDefault(sku, 0) + retained.getOrDefault(sku, 0) != requested.get(sku)) {
                        throw new AssertionError("Completion accounting failed for " + sku);
                    }
                    fulfilled.merge(sku, sold.getOrDefault(sku, 0), Integer::sum);
                }
                if (!quantities(attempt.transaction().scannedSkus()).equals(retained)) {
                    throw new AssertionError("Retained basket does not match unavailable result");
                }
                if (attempt.transaction().status() == TransactionStatus.OPEN && retained.isEmpty()) {
                    throw new AssertionError("Open transaction has no retained items");
                }
                if (attempt.transaction().status() == TransactionStatus.COMPLETED && !retained.isEmpty()) {
                    throw new AssertionError("Completed transaction retained unavailable items");
                }
            }
            Map<String, Integer> finalStock = new HashMap<>();
            store.lowStock(initialStock + 1).forEach(alert -> finalStock.put(alert.sku(), alert.currentStock()));
            for (String sku : List.of("SKU-000001", "SKU-000002", "SKU-000003")) {
                int stock = finalStock.getOrDefault(sku, -1);
                if (stock < 0 || initialStock - stock != fulfilled.getOrDefault(sku, 0)) {
                    throw new AssertionError("Inventory invariant failed for " + sku + ": stock=" + stock);
                }
            }
            System.out.println("Inventory invariant passed for " + completed.size() + " concurrent mixed-basket completions");
        }
    }

    private static Attempt complete(TransactionService transactions, String stationId, List<String> basket) {
        Transaction transaction = transactions.start(stationId);
        basket.forEach(sku -> transactions.scan(transaction.transactionId(), sku));
        return new Attempt(basket, transactions.complete(transaction.transactionId()), transactions.status(transaction.transactionId()));
    }

    private static Map<String, Integer> quantities(List<String> skus) {
        Map<String, Integer> quantities = new HashMap<>();
        skus.forEach(sku -> quantities.merge(sku, 1, Integer::sum));
        return quantities;
    }

    private record Attempt(List<String> basket, CompletionResult result, Transaction transaction) { }
}
