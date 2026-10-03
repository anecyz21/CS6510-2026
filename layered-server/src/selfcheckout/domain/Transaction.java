package selfcheckout.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class Transaction {
    private final String transactionId;
    private final String stationId;
    private final Instant startedAt;
    private final List<String> scannedSkus = new ArrayList<>();
    private TransactionStatus status = TransactionStatus.OPEN;

    public Transaction(String transactionId, String stationId) {
        if (transactionId == null || transactionId.isBlank() || stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("Transaction ID and station ID are required");
        }
        this.transactionId = transactionId;
        this.stationId = stationId;
        this.startedAt = Instant.now();
    }
    public String transactionId() { return transactionId; }
    public String stationId() { return stationId; }
    public Instant startedAt() { return startedAt; }
    public TransactionStatus status() { return status; }
    public List<String> scannedSkus() { return List.copyOf(scannedSkus); }
    public void scan(String sku) { if (status != TransactionStatus.OPEN) throw new IllegalStateException("Transaction is not open"); scannedSkus.add(sku); }
    public void removeFulfilled(Map<String, Integer> quantities) {
        if (status != TransactionStatus.OPEN) throw new IllegalStateException("Transaction is not open");
        var remaining = new java.util.HashMap<>(quantities);
        for (Iterator<String> iterator = scannedSkus.iterator(); iterator.hasNext();) {
            String sku = iterator.next();
            int count = remaining.getOrDefault(sku, 0);
            if (count > 0) {
                iterator.remove();
                remaining.put(sku, count - 1);
            }
        }
        if (remaining.values().stream().anyMatch(count -> count != 0)) {
            throw new IllegalArgumentException("Cannot remove units that are not in the basket");
        }
    }
    public void complete() { if (status != TransactionStatus.OPEN) throw new IllegalStateException("Transaction is not open"); status = TransactionStatus.COMPLETED; }
}
