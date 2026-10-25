package edu.northeastern.cs6510.checkout;

import edu.northeastern.cs6510.checkout.api.ApiException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Checkout-owned persistence only. Every statement names Checkout's own tables. */
@Repository
public class CheckoutRepository {
  private final JdbcTemplate db;

  public CheckoutRepository(JdbcTemplate db) { this.db = db; }

  public Transaction start(String stationId) {
    String id = UUID.randomUUID().toString();
    Instant startedAt = Instant.now();
    db.update("INSERT INTO checkout_transactions(transaction_id, station_id, status, started_at) VALUES (?, ?, 'OPEN', ?)", id, stationId, startedAt);
    return new Transaction(id, stationId, "OPEN", startedAt, null);
  }

  public Optional<Transaction> transaction(String transactionId) {
    return db.query("SELECT transaction_id, station_id, status, started_at, completed_at FROM checkout_transactions WHERE transaction_id = ?", (rs, row) -> new Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()), transactionId).stream().findFirst();
  }

  /** Non-locking check used to give transaction errors precedence over SKU outcomes. */
  public void requireOpen(String transactionId) {
    String status = db.query("SELECT status FROM checkout_transactions WHERE transaction_id = ?", rs -> rs.next() ? rs.getString(1) : null, transactionId);
    if (status == null) throw ApiException.transactionNotFound(transactionId);
    if (!"OPEN".equals(status)) throw ApiException.transactionNotOpen(transactionId);
  }

  public Totals basketTotals(String transactionId) { return db.queryForObject("SELECT COALESCE(SUM(quantity),0), COALESCE(SUM(quantity * unit_price_cents),0) FROM basket_lines WHERE transaction_id=?", (rs, row) -> new Totals(rs.getInt(1), rs.getLong(2)), transactionId); }

  public record Transaction(String transactionId, String stationId, String status, Instant startedAt, Instant completedAt) { }
  public record Totals(int itemCount, long cents) { }
}
