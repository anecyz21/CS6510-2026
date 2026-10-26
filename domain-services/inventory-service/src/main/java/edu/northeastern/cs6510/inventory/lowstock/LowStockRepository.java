package edu.northeastern.cs6510.inventory.lowstock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads physical stock below a threshold and when each SKU crossed it; never counts reservations (FR-026). */
@Repository
public class LowStockRepository {
  private final JdbcTemplate db;

  public LowStockRepository(JdbcTemplate db) { this.db = db; }

  public record Position(String sku, int physicalStock) { }

  public List<Position> below(int threshold) {
    return db.query("SELECT sku, physical_stock FROM inventory_positions WHERE physical_stock < ? ORDER BY sku",
        (rs, n) -> new Position(rs.getString(1), rs.getInt(2)), threshold);
  }

  /** For each SKU, the time of its most recent SALE that left physical stock below the threshold. */
  public Map<String, Instant> lastSaleBelow(int threshold) {
    Map<String, Instant> out = new HashMap<>();
    db.query("SELECT m.sku, MAX(m.occurred_at) FROM inventory_movements m JOIN inventory_positions p ON p.sku = m.sku "
            + "WHERE p.physical_stock < ? AND m.movement_type = 'SALE' AND m.physical_after < ? GROUP BY m.sku",
        rs -> {
          Timestamp at = rs.getTimestamp(2);
          if (at != null) out.put(rs.getString(1), at.toInstant());
        }, threshold, threshold);
    return out;
  }
}
