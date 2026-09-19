package edu.northeastern.cs6510.selfcheckout.transaction;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TransactionStore {
    private final JdbcTemplate jdbcTemplate;

    public TransactionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Transaction createOpen(String stationId) {
        String transactionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        jdbcTemplate.update("INSERT INTO checkout_transactions "
                        + "(transaction_id, station_id, status, item_count, running_total_cents, started_at) "
                        + "VALUES (?, ?, 'OPEN', 0, 0, ?)", transactionId, stationId, Timestamp.from(startedAt));
        return new Transaction(transactionId, stationId, TransactionStatus.OPEN, Map.of(), Map.of(), 0, 0, startedAt, null);
    }

    public Optional<Transaction> findById(String transactionId) {
        return jdbcTemplate.query("SELECT transaction_id, station_id, status, item_count, running_total_cents, started_at, completed_at FROM checkout_transactions WHERE transaction_id = ?",
                (rows, rowNumber) -> mapTransaction(rows.getString("transaction_id"), rows.getString("station_id"), rows.getString("status"), rows.getInt("item_count"), rows.getLong("running_total_cents"), rows.getTimestamp("started_at"), rows.getTimestamp("completed_at")), transactionId).stream().findFirst();
    }

    @Transactional
    public Transaction addScan(String transactionId, String sku, long unitPriceCents, boolean claimed) {
        jdbcTemplate.update("INSERT INTO transaction_lines (transaction_id, sku, quantity, unit_price_cents) VALUES (?, ?, 1, ?) ON DUPLICATE KEY UPDATE quantity = quantity + 1", transactionId, sku, unitPriceCents);
        jdbcTemplate.update("UPDATE checkout_transactions SET item_count = item_count + 1, running_total_cents = running_total_cents + ? WHERE transaction_id = ?", unitPriceCents, transactionId);
        return findById(transactionId).orElseThrow();
    }

    @Transactional
    public Transaction markCompleted(String transactionId, Instant completedAt) {
        jdbcTemplate.update("UPDATE checkout_transactions SET status = 'COMPLETED', completed_at = ? WHERE transaction_id = ?", Timestamp.from(completedAt), transactionId);
        return findById(transactionId).orElseThrow();
    }

    @Transactional
    public Transaction markCancelled(String transactionId) {
        jdbcTemplate.update("UPDATE checkout_transactions SET status = 'CANCELLED' WHERE transaction_id = ?", transactionId);
        return findById(transactionId).orElseThrow();
    }

    private Transaction mapTransaction(String id, String station, String status, int itemCount, long total, Timestamp startedAt, Timestamp completedAt) {
        Map<String, Integer> scanned = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT sku, quantity FROM transaction_lines WHERE transaction_id = ? ORDER BY sku", (RowCallbackHandler) rs -> scanned.put(rs.getString("sku"), rs.getInt("quantity")), id);
        Map<String, Integer> claimed = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT sku, quantity FROM inventory_claims WHERE transaction_id = ? ORDER BY sku", (RowCallbackHandler) rs -> claimed.put(rs.getString("sku"), rs.getInt("quantity")), id);
        return new Transaction(id, station, TransactionStatus.valueOf(status), scanned, claimed, itemCount, total,
                startedAt.toInstant(), completedAt == null ? null : completedAt.toInstant());
    }
}
