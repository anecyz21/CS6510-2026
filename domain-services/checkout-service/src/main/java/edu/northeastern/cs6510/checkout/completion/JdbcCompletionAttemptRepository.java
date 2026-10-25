package edu.northeastern.cs6510.checkout.completion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC implementation over Checkout's own tables only. */
@Repository
public class JdbcCompletionAttemptRepository implements CompletionAttemptRepository {
  private static final int MAX_ERROR_LENGTH = 512;
  private final JdbcTemplate db;

  public JdbcCompletionAttemptRepository(JdbcTemplate db) { this.db = db; }

  @Override
  public Optional<TransactionLock> lockTransaction(String transactionId) {
    return db.query("SELECT status, active_attempt_id FROM checkout_transactions WHERE transaction_id = ? FOR UPDATE",
        (rs, row) -> new TransactionLock(rs.getString(1), rs.getString(2)), transactionId).stream().findFirst();
  }

  @Override
  public List<AttemptLine> basketLines(String transactionId) {
    return db.query("SELECT sku, quantity, unit_price_cents, name FROM basket_lines WHERE transaction_id = ? ORDER BY sku",
        (rs, row) -> new AttemptLine(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getString(4), null), transactionId);
  }

  @Override
  public void insertAttemptWithLines(String attemptId, String transactionId, List<AttemptLine> lines, Instant now) {
    Timestamp at = Timestamp.from(now);
    db.update("INSERT INTO completion_attempts (attempt_id, transaction_id, state, created_at, updated_at, next_retry_at) VALUES (?, ?, 'RESERVING', ?, ?, ?)",
        attemptId, transactionId, at, at, at);
    db.batchUpdate("INSERT INTO completion_attempt_lines (attempt_id, sku, requested_quantity, unit_price_cents, name) VALUES (?, ?, ?, ?, ?)",
        lines, lines.size(), (ps, line) -> {
          ps.setString(1, attemptId);
          ps.setString(2, line.sku());
          ps.setInt(3, line.requestedQuantity());
          ps.setLong(4, line.unitPriceCents());
          ps.setString(5, line.name());
        });
    db.update("UPDATE checkout_transactions SET active_attempt_id = ?, version = version + 1 WHERE transaction_id = ?", attemptId, transactionId);
  }

  @Override
  public Optional<CompletionAttempt> find(String attemptId) {
    List<AttemptLine> lines = db.query(
        "SELECT sku, requested_quantity, unit_price_cents, name, reserved_quantity FROM completion_attempt_lines WHERE attempt_id = ? ORDER BY sku",
        (rs, row) -> new AttemptLine(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getString(4), (Integer) rs.getObject(5)), attemptId);
    return db.query("SELECT attempt_id, transaction_id, state, created_at, updated_at, finalized_at, step_failures, next_retry_at, needs_attention, last_error "
        + "FROM completion_attempts WHERE attempt_id = ?", (rs, row) -> attempt(rs, lines), attemptId).stream().findFirst();
  }

  @Override
  public Optional<CompletionAttempt> findActiveForTransaction(String transactionId) {
    String active = db.query("SELECT active_attempt_id FROM checkout_transactions WHERE transaction_id = ?",
        rs -> rs.next() ? rs.getString(1) : null, transactionId);
    return active == null ? Optional.empty() : find(active);
  }

  @Override
  public boolean transition(String attemptId, AttemptState from, AttemptState to, Instant now) {
    return db.update("UPDATE completion_attempts SET state = ?, updated_at = ?, step_failures = 0 WHERE attempt_id = ? AND state = ?",
        to.name(), Timestamp.from(now), attemptId, from.name()) == 1;
  }

  @Override
  public void storeReserved(String attemptId, Map<String, Integer> reservedBySku) {
    for (Map.Entry<String, Integer> entry : reservedBySku.entrySet()) {
      db.update("UPDATE completion_attempt_lines SET reserved_quantity = ? WHERE attempt_id = ? AND sku = ?",
          entry.getValue(), attemptId, entry.getKey());
    }
  }

  @Override
  public void clearActiveAttempt(String transactionId, String attemptId) {
    db.update("UPDATE checkout_transactions SET active_attempt_id = NULL, version = version + 1 WHERE transaction_id = ? AND active_attempt_id = ?",
        transactionId, attemptId);
  }

  @Override
  public boolean finalizeAttempt(CompletionAttempt attempt, Instant now) {
    Timestamp at = Timestamp.from(now);
    if (db.update("UPDATE completion_attempts SET state = 'FINALIZED', finalized_at = ?, updated_at = ?, step_failures = 0 WHERE attempt_id = ? AND state = 'COMMITTING'",
        at, at, attempt.attemptId()) != 1) {
      return false;
    }
    db.update("INSERT INTO receipts (attempt_id, transaction_id, completed_at) VALUES (?, ?, ?)", attempt.attemptId(), attempt.transactionId(), at);
    for (AttemptLine line : attempt.lines()) {
      int purchased = line.reservedQuantity() == null ? 0 : line.reservedQuantity();
      if (purchased == 0) continue;
      db.update("INSERT INTO receipt_lines (attempt_id, sku, quantity, unit_price_cents, name) VALUES (?, ?, ?, ?, ?)",
          attempt.attemptId(), line.sku(), purchased, line.unitPriceCents(), line.name());
      // The basket was frozen by this attempt, so it still holds at least the requested quantity.
      if (db.update("UPDATE basket_lines SET quantity = quantity - ? WHERE transaction_id = ? AND sku = ? AND quantity > ?",
          purchased, attempt.transactionId(), line.sku(), purchased) == 0
          && db.update("DELETE FROM basket_lines WHERE transaction_id = ? AND sku = ? AND quantity = ?",
              attempt.transactionId(), line.sku(), purchased) == 0) {
        // Rolls the whole finalize back: the frozen basket no longer covers the purchase.
        throw new IllegalStateException("basket line " + line.sku() + " of " + attempt.transactionId() + " is smaller than the purchase");
      }
    }
    Integer remaining = db.queryForObject("SELECT COUNT(*) FROM basket_lines WHERE transaction_id = ?", Integer.class, attempt.transactionId());
    if (remaining != null && remaining == 0) {
      db.update("UPDATE checkout_transactions SET status = 'COMPLETED', completed_at = ? WHERE transaction_id = ?", at, attempt.transactionId());
    }
    clearActiveAttempt(attempt.transactionId(), attempt.attemptId());
    return true;
  }

  @Override
  public void recordStepFailure(String attemptId, String error, Instant nextRetryAt, Instant now) {
    db.update("UPDATE completion_attempts SET step_failures = step_failures + 1, last_error = ?, next_retry_at = ?, updated_at = ? WHERE attempt_id = ?",
        truncate(error), Timestamp.from(nextRetryAt), Timestamp.from(now), attemptId);
  }

  @Override
  public void markNeedsAttention(String attemptId, String error, Instant now) {
    db.update("UPDATE completion_attempts SET needs_attention = TRUE, last_error = ?, updated_at = ? WHERE attempt_id = ?",
        truncate(error), Timestamp.from(now), attemptId);
  }

  private static CompletionAttempt attempt(ResultSet rs, List<AttemptLine> lines) throws SQLException {
    return new CompletionAttempt(rs.getString(1), rs.getString(2), AttemptState.valueOf(rs.getString(3)),
        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)), rs.getInt(7),
        instant(rs.getTimestamp(8)), rs.getBoolean(9), rs.getString(10), lines);
  }

  private static Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }

  private static String truncate(String error) {
    if (error == null) return null;
    return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
  }
}
