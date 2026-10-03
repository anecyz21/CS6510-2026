package selfcheckout.dataaccess;

import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.LowStockAlert;
import selfcheckout.domain.Transaction;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public interface SelfCheckoutStore {
    List<CatalogItem> catalog();
    CatalogItem findItem(String sku);
    Transaction createTransaction(String stationId);
    Transaction findTransaction(String transactionId);
    Fulfillment fulfillAvailable(Map<String, Integer> quantities);
    List<LowStockAlert> lowStock(int threshold);

    record Fulfillment(Map<String, Integer> fulfilled, Map<String, Integer> unavailable) {
        public Fulfillment {
            fulfilled = Map.copyOf(new LinkedHashMap<>(fulfilled));
            unavailable = Map.copyOf(new LinkedHashMap<>(unavailable));
        }
    }
}
