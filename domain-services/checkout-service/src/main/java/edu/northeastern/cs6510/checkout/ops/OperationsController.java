package edu.northeastern.cs6510.checkout.ops;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout operations API v1 (contracts/checkout-operations-api.md): Checkout's read-only half of
 * the settled-state reconciliation. Not routed by the gateway.
 */
@RestController
public class OperationsController {
  private final JdbcTemplate db;

  public OperationsController(JdbcTemplate db) { this.db = db; }

  @Transactional(readOnly = true)
  @GetMapping("/internal/v1/ops/reconciliation-export")
  public Map<String, Object> reconciliationExport() {
    Integer unresolved = db.queryForObject(
        "SELECT COUNT(*) FROM completion_attempts WHERE state IN ('RESERVING', 'COMMITTING', 'CANCELLING')", Integer.class);
    Integer flags = db.queryForObject("SELECT COUNT(*) FROM checkout_transactions WHERE active_attempt_id IS NOT NULL", Integer.class);
    Map<String, Long> quantities = new LinkedHashMap<>();
    db.query("SELECT sku, SUM(quantity) FROM receipt_lines GROUP BY sku ORDER BY sku", rs -> { quantities.put(rs.getString(1), rs.getLong(2)); });
    List<String> finalized = db.queryForList("SELECT attempt_id FROM completion_attempts WHERE state = 'FINALIZED' ORDER BY attempt_id", String.class);
    Map<String, Object> export = new LinkedHashMap<>();
    export.put("generatedAt", Instant.now().toString());
    export.put("unresolvedAttempts", unresolved == null ? 0 : unresolved);
    export.put("activeAttemptFlags", flags == null ? 0 : flags);
    export.put("receiptQuantityBySku", quantities);
    export.put("finalizedAttemptIds", finalized);
    return export;
  }
}
