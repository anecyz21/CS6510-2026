package edu.northeastern.cs6510.checkout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Checkout-owned persistence only. Cross-domain reads are introduced separately through views. */
@Repository
public class CheckoutRepository {
  private final JdbcTemplate db;

  public CheckoutRepository(JdbcTemplate db) { this.db = db; }

  @Transactional
  public Transaction start(String stationId) {
    if (stationId == null || stationId.isBlank()) throw new IllegalArgumentException("stationId must be nonblank");
    String id = UUID.randomUUID().toString();
    Instant startedAt = Instant.now();
    db.update("INSERT INTO checkout_transactions(transaction_id, station_id, status, started_at) VALUES (?, ?, 'OPEN', ?)", id, stationId, startedAt);
    return new Transaction(id, stationId, "OPEN", startedAt, null);
  }

  public Transaction transaction(String transactionId) {
    return db.queryForObject("SELECT transaction_id, station_id, status, started_at, completed_at FROM checkout_transactions WHERE transaction_id = ?", (rs, row) -> new Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()), transactionId);
  }

  /** Reads only Catalog's published view; prices are copied into Checkout's own basket. */
  public Optional<CatalogItem> activeCatalogItem(String sku) {
    if (sku == null || sku.isBlank()) return Optional.empty();
    return db.query("SELECT sku, name, price_cents FROM catalog.active_items WHERE sku = ?", (rs, row) -> new CatalogItem(rs.getString(1), rs.getString(2), rs.getLong(3)), sku).stream().findFirst();
  }

  @Transactional
  public void addBasketUnit(String transactionId, String sku, long unitPriceCents) {
    if (sku == null || sku.isBlank() || unitPriceCents < 0) throw new IllegalArgumentException("invalid basket line");
    int updated = db.update("UPDATE basket_lines SET quantity = quantity + 1 WHERE transaction_id = ? AND sku = ?", transactionId, sku);
    if (updated == 0) db.update("INSERT INTO basket_lines(transaction_id, sku, quantity, unit_price_cents) VALUES (?, ?, 1, ?)", transactionId, sku, unitPriceCents);
  }

  public boolean isOpen(String transactionId) {
    Integer count = db.queryForObject("SELECT COUNT(*) FROM checkout_transactions WHERE transaction_id = ? AND status = 'OPEN'", Integer.class, transactionId);
    return count != null && count == 1;
  }

  public void appendAcceptedScan(String transactionId, String sku) {
    db.update("INSERT INTO accepted_scan_events(transaction_id, sku) VALUES (?, ?)", transactionId, sku);
  }

  public List<ReceiptLine> basketLines(String transactionId) { return db.query("SELECT sku, quantity, unit_price_cents FROM basket_lines WHERE transaction_id = ? ORDER BY sku", (rs, row) -> new ReceiptLine(rs.getString(1), rs.getInt(2), rs.getLong(3)), transactionId); }

  public Totals basketTotals(String transactionId) { return db.queryForObject("SELECT COALESCE(SUM(quantity),0), COALESCE(SUM(quantity * unit_price_cents),0) FROM basket_lines WHERE transaction_id=?", (rs, row) -> new Totals(rs.getInt(1), rs.getLong(2)), transactionId); }
  public Totals receiptTotals(String transactionId) { return db.queryForObject("SELECT COALESCE(SUM(quantity),0), COALESCE(SUM(quantity * unit_price_cents),0) FROM receipt_lines WHERE transaction_id=?", (rs, row) -> new Totals(rs.getInt(1), rs.getLong(2)), transactionId); }

  public void applyFulfillment(String transactionId, List<Fulfillment> results) {
    db.update("INSERT IGNORE INTO receipts(transaction_id, completed_at) VALUES (?, CURRENT_TIMESTAMP(6))", transactionId);
    for (Fulfillment result : results) if (result.fulfilledQuantity() > 0) {
      ReceiptLine line = db.queryForObject("SELECT sku, ?, unit_price_cents FROM basket_lines WHERE transaction_id = ? AND sku = ?", (rs, row) -> new ReceiptLine(rs.getString(1), rs.getInt(2), rs.getLong(3)), result.fulfilledQuantity(), transactionId, result.sku());
      db.update("INSERT INTO receipt_lines(transaction_id, sku, quantity, unit_price_cents) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)", transactionId, line.sku(), line.quantity(), line.unitPriceCents());
      // A basket line has a positive-quantity constraint: remove fully fulfilled lines directly
      // rather than transiently writing quantity zero and then deleting it.
      if (result.fulfilledQuantity() >= line.quantity()) {
        db.update("DELETE FROM basket_lines WHERE transaction_id = ? AND sku = ?", transactionId, result.sku());
      } else {
        db.update("UPDATE basket_lines SET quantity = quantity - ? WHERE transaction_id = ? AND sku = ?", result.fulfilledQuantity(), transactionId, result.sku());
      }
    }
    Integer remaining = db.queryForObject("SELECT COUNT(*) FROM basket_lines WHERE transaction_id = ?", Integer.class, transactionId);
    if (remaining != null && remaining == 0) db.update("UPDATE checkout_transactions SET status='COMPLETED', completed_at=CURRENT_TIMESTAMP(6) WHERE transaction_id=?", transactionId);
  }

  public List<Fulfillment> fulfill(String transactionId, String requested) { return db.query("CALL inventory.fulfill_checkout(?, ?)", (rs, row) -> new Fulfillment(rs.getString("sku"), rs.getInt("fulfilled_quantity"), rs.getInt("unavailable_quantity")), transactionId, requested); }

  @Transactional
  public void saveReceipt(String transactionId, List<ReceiptLine> lines) {
    Instant completedAt = Instant.now();
    db.update("UPDATE checkout_transactions SET status = 'COMPLETED', completed_at = ? WHERE transaction_id = ? AND status = 'OPEN'", completedAt, transactionId);
    db.update("INSERT INTO receipts(transaction_id, completed_at) VALUES (?, ?)", transactionId, completedAt);
    for (ReceiptLine line : lines) db.update("INSERT INTO receipt_lines(transaction_id, sku, quantity, unit_price_cents) VALUES (?, ?, ?, ?)", transactionId, line.sku(), line.quantity(), line.unitPriceCents());
  }

  public record Transaction(String transactionId, String stationId, String status, Instant startedAt, Instant completedAt) { }
  public record CatalogItem(String sku, String name, long priceCents) { }
  public record ReceiptLine(String sku, int quantity, long unitPriceCents) { }
  public record Fulfillment(String sku, int fulfilledQuantity, int unavailableQuantity) { }
  public record Totals(int itemCount, long cents) { }
}
