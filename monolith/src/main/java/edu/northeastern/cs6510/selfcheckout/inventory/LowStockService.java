package edu.northeastern.cs6510.selfcheckout.inventory;

import edu.northeastern.cs6510.selfcheckout.config.SelfCheckoutProperties;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LowStockService {
    private final JdbcTemplate jdbcTemplate;
    private final SelfCheckoutProperties properties;
    public LowStockService(JdbcTemplate jdbcTemplate, SelfCheckoutProperties properties) { this.jdbcTemplate = jdbcTemplate; this.properties = properties; }
    public LowStockResult getLowStock(Integer requestedThreshold) {
        int threshold = requestedThreshold == null ? properties.lowStockThreshold() : requestedThreshold;
        Instant generatedAt = Instant.now();
        List<LowStockAlert> alerts = jdbcTemplate.query("SELECT i.sku, c.name, i.stock, (SELECT m.occurred_at FROM inventory_movements m WHERE m.sku = i.sku AND m.stock_after < ? ORDER BY m.movement_id DESC LIMIT 1) AS triggered_at FROM inventory i JOIN catalog_items c ON c.sku = i.sku WHERE i.stock < ? ORDER BY i.sku",
                (rs, row) -> new LowStockAlert(rs.getString("sku"), rs.getString("name"), rs.getInt("stock"), threshold,
                        rs.getTimestamp("triggered_at") == null ? generatedAt : rs.getTimestamp("triggered_at").toInstant()), threshold, threshold);
        return new LowStockResult(threshold, generatedAt, alerts);
    }
}
