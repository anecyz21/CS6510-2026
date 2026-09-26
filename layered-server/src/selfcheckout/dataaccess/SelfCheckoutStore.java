package selfcheckout.dataaccess;

import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.LowStockAlert;
import selfcheckout.domain.Transaction;

import java.util.List;
import java.util.Map;

public interface SelfCheckoutStore {
    List<CatalogItem> catalog();
    CatalogItem findItem(String sku);
    Transaction createTransaction(String stationId);
    Transaction findTransaction(String transactionId);
    boolean decrementIfAvailable(Map<String, Integer> quantities);
    List<LowStockAlert> lowStock(int threshold);
}
