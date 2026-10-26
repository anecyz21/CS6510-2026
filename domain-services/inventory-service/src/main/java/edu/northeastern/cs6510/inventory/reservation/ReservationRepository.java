package edu.northeastern.cs6510.inventory.reservation;

import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Inventory-owned reservation persistence. Every method runs inside the caller's single local
 * transaction. Locks are always taken on the reservation row first, then on positions in
 * <em>descending</em> SKU order, one row per statement (research R11, revised for the completion
 * bottleneck). Any fixed order prevents lock cycles; descending order takes the low-numbered SKUs,
 * which the measured workload makes hot, last, so they are held only until the commit that follows.
 */
@Repository
public class ReservationRepository {
  private final JdbcTemplate db;

  public ReservationRepository(JdbcTemplate db) { this.db = db; }

  /** Locked current state of an existing reservation, or empty when the id is unknown. */
  public Optional<ReservationStateV1> lockReservation(String attemptId) {
    return db.query("SELECT state FROM reservations WHERE attempt_id = ? FOR UPDATE",
        (rs, row) -> ReservationStateV1.valueOf(rs.getString(1)), attemptId).stream().findFirst();
  }

  /**
   * Inserts a new reservation row, or does nothing when the id exists. A concurrent insert of the
   * same id waits on the row lock, so exactly one caller creates it. Returns true when created.
   */
  public boolean insertReservation(String attemptId, String transactionId, ReservationStateV1 state, Instant now) {
    return db.update("INSERT IGNORE INTO reservations (attempt_id, transaction_id, state, created_at) VALUES (?, ?, ?, ?)",
        attemptId, transactionId, state.name(), Timestamp.from(now)) == 1;
  }

  /**
   * Reserves up to {@code quantity} of what is available, in one statement that locks only this
   * row, and returns the quantity granted (0 for an unknown or exhausted SKU). The grant travels back
   * through {@code LAST_INSERT_ID(expr)}, which the driver reports as the statement's generated key,
   * so no extra round trip is made while the row is locked.
   */
  public int reserveUpTo(String sku, int quantity) {
    KeyHolder grant = new GeneratedKeyHolder();
    int rows = db.update(connection -> {
      PreparedStatement s = connection.prepareStatement(
          "UPDATE inventory_positions SET reserved_stock = reserved_stock + LAST_INSERT_ID(LEAST(?, GREATEST(physical_stock - reserved_stock, 0))) WHERE sku = ?",
          Statement.RETURN_GENERATED_KEYS);
      s.setInt(1, quantity);
      s.setString(2, sku);
      return s;
    }, grant);
    return rows == 0 ? 0 : generated(grant);
  }

  /** Returns a reserved quantity to availability without touching physical stock. */
  public boolean releaseLine(String sku, int quantity) {
    return db.update("UPDATE inventory_positions SET reserved_stock = reserved_stock - ? WHERE sku = ? AND reserved_stock >= ?",
        quantity, sku, quantity) == 1;
  }

  /** Writes every line of a new reservation in one batched statement. */
  public void insertLines(String attemptId, List<ReservationLineV1> lines) {
    db.batchUpdate("INSERT INTO reservation_lines (attempt_id, sku, requested_quantity, reserved_quantity) VALUES (?, ?, ?, ?)",
        lines, lines.size(), (ps, line) -> {
          ps.setString(1, attemptId);
          ps.setString(2, line.sku());
          ps.setInt(3, line.requestedQuantity());
          ps.setInt(4, line.reservedQuantity());
        });
  }

  /** Queues the held lines of a committed reservation for {@link SaleApplier}; touches no position row. */
  public void enqueueSales(String attemptId, List<ReservationLineV1> held, Instant committedAt) {
    if (held.isEmpty()) return;
    db.batchUpdate("INSERT INTO sale_queue (attempt_id, sku, quantity, committed_at) VALUES (?, ?, ?, ?)",
        held, held.size(), (ps, line) -> {
          ps.setString(1, attemptId);
          ps.setString(2, line.sku());
          ps.setInt(3, line.reservedQuantity());
          ps.setTimestamp(4, Timestamp.from(committedAt));
        });
  }

  /**
   * Converts {@code quantity} of reserved stock into a physical decrement, the only path that lowers
   * physical stock for a sale, and returns the physical stock after it. Empty when the row does not
   * hold that much reserved stock.
   */
  public Optional<Integer> applySale(String sku, int quantity) {
    KeyHolder after = new GeneratedKeyHolder();
    int rows = db.update(connection -> {
      PreparedStatement s = connection.prepareStatement(
          "UPDATE inventory_positions SET physical_stock = LAST_INSERT_ID(physical_stock - ?), reserved_stock = reserved_stock - ? WHERE sku = ? AND reserved_stock >= ?",
          Statement.RETURN_GENERATED_KEYS);
      s.setInt(1, quantity);
      s.setInt(2, quantity);
      s.setString(3, sku);
      s.setInt(4, quantity);
      return s;
    }, after);
    return rows == 0 ? Optional.empty() : Optional.of(generated(after));
  }

  public void updateState(String attemptId, ReservationStateV1 state, Instant resolvedAt) {
    db.update("UPDATE reservations SET state = ?, resolved_at = ? WHERE attempt_id = ?",
        state.name(), resolvedAt == null ? null : Timestamp.from(resolvedAt), attemptId);
  }

  public Optional<ReservationV1> find(String attemptId) {
    List<ReservationLineV1> lines = db.query(
        "SELECT sku, requested_quantity, reserved_quantity FROM reservation_lines WHERE attempt_id = ? ORDER BY sku",
        (rs, row) -> new ReservationLineV1(rs.getString(1), rs.getInt(2), rs.getInt(3)), attemptId);
    return db.query("SELECT attempt_id, transaction_id, state, created_at, resolved_at FROM reservations WHERE attempt_id = ?",
        (rs, row) -> new ReservationV1(rs.getString(1), rs.getString(2), ReservationStateV1.valueOf(rs.getString(3)), lines,
            rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
        attemptId).stream().findFirst();
  }

  /** The value set by {@code LAST_INSERT_ID(expr)}; MySQL reports a zero value as no key. */
  private static int generated(KeyHolder keys) {
    return keys.getKeyList().isEmpty() ? 0 : ((Number) keys.getKeyList().get(0).values().iterator().next()).intValue();
  }
}
