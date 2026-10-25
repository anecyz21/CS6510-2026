package edu.northeastern.cs6510.inventory.reservation;

import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Inventory-owned reservation persistence. Every method runs inside the caller's single local
 * transaction; locks are always taken on the reservation row first, then on positions in ascending
 * SKU order (research R11), so two commands never wait on each other in a cycle.
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

  /** Locks the positions of the given SKUs in ascending SKU order. Unknown SKUs are absent. */
  public List<Position> lockPositions(Collection<String> skus) {
    if (skus.isEmpty()) return List.of();
    String marks = skus.stream().map(s -> "?").collect(Collectors.joining(","));
    return db.query("SELECT sku, physical_stock, reserved_stock FROM inventory_positions WHERE sku IN (" + marks + ") ORDER BY sku FOR UPDATE",
        (rs, row) -> new Position(rs.getString(1), rs.getInt(2), rs.getInt(3)), skus.toArray());
  }

  /** Adds {@code quantity} to reserved stock only if that much is still available. */
  public boolean reserve(String sku, int quantity) {
    return db.update("UPDATE inventory_positions SET reserved_stock = reserved_stock + ? WHERE sku = ? AND physical_stock - reserved_stock >= ?",
        quantity, sku, quantity) == 1;
  }

  /** Converts a reserved quantity into a physical decrement: the only path that lowers physical stock for a sale. */
  public boolean commitLine(String sku, int quantity) {
    return db.update("UPDATE inventory_positions SET physical_stock = physical_stock - ?, reserved_stock = reserved_stock - ? WHERE sku = ? AND reserved_stock >= ?",
        quantity, quantity, sku, quantity) == 1;
  }

  /** Returns a reserved quantity to availability without touching physical stock. */
  public boolean releaseLine(String sku, int quantity) {
    return db.update("UPDATE inventory_positions SET reserved_stock = reserved_stock - ? WHERE sku = ? AND reserved_stock >= ?",
        quantity, sku, quantity) == 1;
  }

  public void insertLine(String attemptId, String sku, int requested, int reserved) {
    db.update("INSERT INTO reservation_lines (attempt_id, sku, requested_quantity, reserved_quantity) VALUES (?, ?, ?, ?)",
        attemptId, sku, requested, reserved);
  }

  public void insertSaleMovement(String attemptId, String sku, int quantityDelta, int physicalAfter, Instant now) {
    db.update("INSERT INTO inventory_movements (sku, attempt_id, movement_type, quantity_delta, physical_after, occurred_at) VALUES (?, ?, 'SALE', ?, ?, ?)",
        sku, attemptId, quantityDelta, physicalAfter, Timestamp.from(now));
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

  public record Position(String sku, int physicalStock, int reservedStock) {
    public int available() { return physicalStock - reservedStock; }
  }
}
