package edu.northeastern.cs6510.inventory.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory's read-only half of the settled-state reconciliation (data-model R-1, R-2, R-4, R-5).
 * Reconciliation scripts combine it with Checkout's export; nobody reads this database directly.
 */
@RestController
public class InventoryOpsController {
  private final JdbcTemplate db;

  public InventoryOpsController(JdbcTemplate db) { this.db = db; }

  @Transactional(readOnly = true)
  @GetMapping("/internal/v1/ops/reconciliation-export")
  public Map<String, Object> reconciliationExport() {
    List<Map<String, Object>> positions = db.query(
        "SELECT sku, initial_stock, physical_stock, reserved_stock FROM inventory_positions ORDER BY sku",
        (rs, row) -> {
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("sku", rs.getString(1));
          p.put("initialStock", rs.getInt(2));
          p.put("physicalStock", rs.getInt(3));
          p.put("reservedStock", rs.getInt(4));
          return p;
        });
    Integer open = db.queryForObject("SELECT COUNT(*) FROM reservations WHERE state = 'RESERVED'", Integer.class);
    List<String> committed = db.queryForList("SELECT attempt_id FROM reservations WHERE state = 'COMMITTED' ORDER BY attempt_id", String.class);
    Map<String, Long> sales = new LinkedHashMap<>();
    db.query("SELECT sku, -SUM(quantity_delta) FROM inventory_movements WHERE movement_type = 'SALE' GROUP BY sku ORDER BY sku",
        rs -> { sales.put(rs.getString(1), rs.getLong(2)); });
    Map<String, Object> export = new LinkedHashMap<>();
    export.put("positions", positions);
    export.put("openReservations", open == null ? 0 : open);
    export.put("committedAttemptIds", committed);
    export.put("saleQuantityBySku", sales);
    return export;
  }
}
