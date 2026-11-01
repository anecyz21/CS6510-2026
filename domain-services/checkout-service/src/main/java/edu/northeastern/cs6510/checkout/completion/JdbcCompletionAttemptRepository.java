package edu.northeastern.cs6510.checkout.completion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC implementation over Checkout's own tables only. */
@Repository
public class JdbcCompletionAttemptRepository implements CompletionAttemptRepository {
  private static final int MAX_ERROR_LENGTH = 512;
  private static final Duration PARKED_FOR = Duration.ofDays(365);
  private final JdbcTemplate db;

  public JdbcCompletionAttemptRepository(JdbcTemplate db) { this.db = db; }

  @Override
  public Optional<TransactionLock> lockTransaction(String transactionId) {
    return db.query("SELECT status, active_attempt_id, station_id, started_at FROM checkout_transactions WHERE transaction_id = ? FOR UPDATE",
        (rs, row) -> new TransactionLock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
        transactionId).stream().findFirst();
  }

  @Override
  public List<AttemptLine> basketLines(String transactionId) {
    return db.query("SELECT sku, quantity, unit_price_cents, name FROM basket_lines WHERE transaction_id = ? ORDER BY sku",
        (rs, row) -> new AttemptLine(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getString(4), null), transactionId);
  }

  @Override
  public void insertAttemptWithLines(String attemptId, String transactionId, List<AttemptLine> lines, Instant now, Instant resendAt) {
    Timestamp at = Timestamp.from(now);
    db.update("INSERT INTO completion_attempts (attempt_id, transaction_id, state, created_at, updated_at, next_retry_at, last_command_at, command_sends) "
            + "VALUES (?, ?, 'RESERVING', ?, ?, ?, ?, 1)",
        attemptId, transactionId, at, at, Timestamp.from(resendAt), at);
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
    return db.query("SELECT attempt_id, transaction_id, state, created_at, updated_at, finalized_at, command_sends, next_retry_at, needs_attention, last_error "
        + "FROM completion_attempts WHERE attempt_id = ?", (rs, row) -> attempt(rs, lines), attemptId).stream().findFirst();
  }

  @Override
  public Optional<CompletionAttempt> findActiveForTransaction(String transactionId) {
    String active = db.query("SELECT active_attempt_id FROM checkout_transactions WHERE transaction_id = ?",
        rs -> rs.next() ? rs.getString(1) : null, transactionId);
    return active == null ? Optional.empty() : find(active);
  }

  @Override
  public boolean transition(String attemptId, AttemptState from, AttemptState to, Instant now, Instant resendAt) {
    Timestamp at = Timestamp.from(now);
    return db.update("UPDATE completion_attempts SET state = ?, updated_at = ?, last_command_at = ?, command_sends = 1, next_retry_at = ? "
        + "WHERE attempt_id = ? AND state = ?", to.name(), at, at, Timestamp.from(resendAt), attemptId, from.name()) == 1;
  }

  /** One statement for every line: {@code SET reserved_quantity = CASE sku WHEN ? THEN ? ... END}. */
  @Override
  public void storeReserved(String attemptId, Map<String, Integer> reservedBySku) {
    if (reservedBySku.isEmpty()) return;
    StringBuilder sql = new StringBuilder("UPDATE completion_attempt_lines SET reserved_quantity = CASE sku");
    List<Object> args = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : reservedBySku.entrySet()) {
      sql.append(" WHEN ? THEN ?");
      args.add(entry.getKey());
      args.add(entry.getValue());
    }
    sql.append(" END WHERE attempt_id = ? AND sku IN (")
        .append(String.join(",", Collections.nCopies(reservedBySku.size(), "?"))).append(")");
    args.add(attemptId);
    args.addAll(reservedBySku.keySet());
    db.update(sql.toString(), args.toArray());
  }

  @Override
  public void clearActiveAttempt(String transactionId, String attemptId) {
    db.update("UPDATE checkout_transactions SET active_attempt_id = NULL, version = version + 1 WHERE transaction_id = ? AND active_attempt_id = ?",
        transactionId, attemptId);
  }

  /**
   * Set-based, so the statement count does not grow with the basket: the purchased quantities are
   * read from the stored receipt intent ({@code completion_attempt_lines.reserved_quantity}).
   */
  @Override
  public boolean finalizeAttempt(CompletionAttempt attempt, Instant now) {
    Timestamp at = Timestamp.from(now);
    String attemptId = attempt.attemptId();
    String transactionId = attempt.transactionId();
    if (db.update("UPDATE completion_attempts SET state = 'FINALIZED', finalized_at = ?, updated_at = ? "
        + "WHERE attempt_id = ? AND state = 'COMMITTING'", at, at, attemptId) != 1) {
      return false;
    }
    db.update("INSERT INTO receipts (attempt_id, transaction_id, completed_at) VALUES (?, ?, ?)", attemptId, transactionId, at);
    int purchasedLines = db.update("INSERT INTO receipt_lines (attempt_id, sku, quantity, unit_price_cents, name) "
        + "SELECT attempt_id, sku, reserved_quantity, unit_price_cents, name FROM completion_attempt_lines "
        + "WHERE attempt_id = ? AND reserved_quantity > 0", attemptId);
    // The basket was frozen by this attempt, so each purchased line still holds at least its purchase:
    // lines bought in full are deleted, the rest are reduced.
    int emptied = db.update("DELETE b FROM basket_lines b JOIN completion_attempt_lines l ON l.sku = b.sku "
        + "WHERE l.attempt_id = ? AND b.transaction_id = ? AND l.reserved_quantity > 0 AND b.quantity = l.reserved_quantity",
        attemptId, transactionId);
    int reduced = db.update("UPDATE basket_lines b JOIN completion_attempt_lines l ON l.sku = b.sku "
        + "SET b.quantity = b.quantity - l.reserved_quantity "
        + "WHERE l.attempt_id = ? AND b.transaction_id = ? AND l.reserved_quantity > 0 AND b.quantity > l.reserved_quantity",
        attemptId, transactionId);
    if (emptied + reduced != purchasedLines) {
      // Rolls the whole finalize back: the frozen basket no longer covers the purchase.
      throw new IllegalStateException("basket of " + transactionId + " does not cover the purchase of attempt " + attemptId);
    }
    // COMPLETED when nothing is left; the freeze flag is cleared only if this attempt still holds it.
    db.update("UPDATE checkout_transactions SET "
            + "completed_at = IF(EXISTS (SELECT 1 FROM basket_lines WHERE transaction_id = ?), completed_at, ?), "
            + "status = IF(EXISTS (SELECT 1 FROM basket_lines WHERE transaction_id = ?), status, 'COMPLETED'), "
            + "active_attempt_id = IF(active_attempt_id = ?, NULL, active_attempt_id), version = version + 1 "
            + "WHERE transaction_id = ?",
        transactionId, at, transactionId, attemptId, transactionId);
    return true;
  }

  @Override
  public boolean claimResend(String attemptId, AttemptState state, Instant dueAt, Instant now, Instant nextResendAt) {
    Timestamp at = Timestamp.from(now);
    return db.update("UPDATE completion_attempts SET command_sends = command_sends + 1, last_command_at = ?, next_retry_at = ?, updated_at = ? "
            + "WHERE attempt_id = ? AND state = ? AND next_retry_at = ? AND needs_attention = FALSE",
        at, Timestamp.from(nextResendAt), at, attemptId, state.name(), Timestamp.from(dueAt)) == 1;
  }

  @Override
  public void parkForOperator(String attemptId, String error, Instant now) {
    // TIMESTAMP ends in 2038, so "parked" is a year out rather than "forever"; an operator retry makes it due again.
    db.update("UPDATE completion_attempts SET needs_attention = TRUE, last_error = ?, next_retry_at = ?, updated_at = ? WHERE attempt_id = ?",
        truncate(error), Timestamp.from(now.plus(PARKED_FOR)), Timestamp.from(now), attemptId);
  }

  @Override
  public List<CompletionAttempt> findDueForResend(Instant now, int limit) {
    List<String> ids = db.queryForList("SELECT attempt_id FROM completion_attempts WHERE state IN ('RESERVING', 'COMMITTING', 'CANCELLING') "
        + "AND needs_attention = FALSE AND next_retry_at <= ? ORDER BY next_retry_at LIMIT ?", String.class, Timestamp.from(now), limit);
    return ids.stream().map(this::find).flatMap(Optional::stream).toList();
  }

  @Override
  public Map<AttemptState, Long> countByState() {
    Map<AttemptState, Long> counts = new EnumMap<>(AttemptState.class);
    for (AttemptState s : AttemptState.values()) counts.put(s, 0L);
    db.query("SELECT state, COUNT(*) FROM completion_attempts GROUP BY state",
        rs -> { counts.put(AttemptState.valueOf(rs.getString(1)), rs.getLong(2)); });
    return counts;
  }

  @Override
  public List<CompletionAttempt> list(OperatorFilter filter, int limit) {
    String where = switch (filter) {
      case UNRESOLVED -> "WHERE state IN ('RESERVING', 'COMMITTING', 'CANCELLING')";
      case NEEDS_ATTENTION -> "WHERE needs_attention = TRUE";
      case ALL -> "";
    };
    List<String> ids = db.queryForList("SELECT attempt_id FROM completion_attempts " + where + " ORDER BY created_at DESC LIMIT ?", String.class, limit);
    return ids.stream().map(id -> find(id).orElseThrow()).toList();
  }

  @Override
  public boolean requestRetry(String attemptId, Instant now) {
    return db.update("UPDATE completion_attempts SET next_retry_at = ?, needs_attention = FALSE, updated_at = ? "
        + "WHERE attempt_id = ? AND state IN ('RESERVING', 'COMMITTING', 'CANCELLING')", Timestamp.from(now), Timestamp.from(now), attemptId) == 1;
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
