package edu.northeastern.cs6510.selfcheckout.transaction;

import java.time.Instant;
import java.util.Map;

public record Transaction(
        String transactionId,
        String stationId,
        TransactionStatus status,
        Map<String, Integer> scannedQty,
        Map<String, Integer> claimedQty,
        int itemCount,
        long runningTotalCents,
        Instant startedAt,
        Instant completedAt) {
    public Transaction {
        if (transactionId == null || transactionId.isBlank() || stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("transactionId and stationId must not be blank");
        }
        if (itemCount < 0 || runningTotalCents < 0 || status == null || startedAt == null) {
            throw new IllegalArgumentException("transaction fields are invalid");
        }
        Map<String, Integer> normalizedScannedQty = Map.copyOf(scannedQty == null ? Map.of() : scannedQty);
        Map<String, Integer> normalizedClaimedQty = Map.copyOf(claimedQty == null ? Map.of() : claimedQty);
        normalizedClaimedQty.forEach((sku, claimed) -> {
            if (claimed < 0 || claimed > normalizedScannedQty.getOrDefault(sku, 0)) {
                throw new IllegalArgumentException("claimed quantity cannot exceed scanned quantity");
            }
        });
        scannedQty = normalizedScannedQty;
        claimedQty = normalizedClaimedQty;
    }
}
