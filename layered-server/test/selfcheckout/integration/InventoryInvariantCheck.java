package selfcheckout.integration;

import selfcheckout.dataaccess.InMemorySelfCheckoutStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Lightweight concurrent check for the atomic inventory invariant. */
public final class InventoryInvariantCheck {
    public static void main(String[] args) throws Exception {
        int initialStock = 1_000;
        var store = new InMemorySelfCheckoutStore(1, initialStock);
        try (var executor = Executors.newFixedThreadPool(32)) {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 10_000)
                    .<Callable<Boolean>>mapToObj(ignored -> () -> store.decrementIfAvailable(Map.of("SKU-000001", 1)))
                    .toList();
            long successes = executor.invokeAll(attempts).stream().filter(result -> {
                try { return result.get(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }).count();
            int finalStock = store.lowStock(initialStock + 1).getFirst().currentStock();
            if (successes != initialStock || finalStock != 0 || initialStock - finalStock != successes) {
                throw new AssertionError("Inventory invariant failed: successes=" + successes + ", finalStock=" + finalStock);
            }
            System.out.println("Inventory invariant passed: " + successes + " atomic decrements, final stock " + finalStock);
        }
    }
}
