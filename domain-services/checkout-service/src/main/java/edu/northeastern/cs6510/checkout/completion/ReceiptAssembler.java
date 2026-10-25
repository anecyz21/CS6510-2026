package edu.northeastern.cs6510.checkout.completion;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the public {@code Receipt} for one finalized attempt (FR-014, research R13). Lines, item
 * count and total cover only this attempt's purchases; transactionId, stationId and startedAt are
 * the transaction's, and completedAt is this attempt's finalization time. Amounts are exact cents.
 */
@Component
public class ReceiptAssembler {
  private final JdbcTemplate db;

  public ReceiptAssembler(JdbcTemplate db) { this.db = db; }

  public Map<String, Object> receipt(String attemptId) {
    List<Map<String, Object>> lines = db.query(
        "SELECT sku, name, unit_price_cents, quantity FROM receipt_lines WHERE attempt_id = ? ORDER BY sku",
        (rs, row) -> {
          Map<String, Object> line = new LinkedHashMap<>();
          line.put("sku", rs.getString(1));
          line.put("name", rs.getString(2));
          line.put("unitPrice", money(rs.getLong(3)));
          line.put("quantity", rs.getInt(4));
          return line;
        }, attemptId);
    int itemCount = lines.stream().mapToInt(l -> (Integer) l.get("quantity")).sum();
    Long totalCents = db.queryForObject("SELECT COALESCE(SUM(quantity * unit_price_cents), 0) FROM receipt_lines WHERE attempt_id = ?", Long.class, attemptId);
    return db.queryForObject(
        "SELECT t.transaction_id, t.station_id, t.started_at, r.completed_at FROM receipts r "
            + "JOIN checkout_transactions t ON t.transaction_id = r.transaction_id WHERE r.attempt_id = ?",
        (rs, row) -> {
          Map<String, Object> receipt = new LinkedHashMap<>();
          receipt.put("transactionId", rs.getString(1));
          receipt.put("stationId", rs.getString(2));
          receipt.put("itemCount", itemCount);
          receipt.put("totalAmount", money(totalCents == null ? 0 : totalCents));
          receipt.put("startedAt", rs.getTimestamp(3).toInstant().toString());
          receipt.put("completedAt", rs.getTimestamp(4).toInstant().toString());
          receipt.put("lines", lines);
          return receipt;
        }, attemptId);
  }

  private static BigDecimal money(long cents) { return BigDecimal.valueOf(cents, 2); }
}
