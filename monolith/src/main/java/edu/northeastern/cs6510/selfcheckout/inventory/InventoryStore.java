package edu.northeastern.cs6510.selfcheckout.inventory;

import java.util.Optional;
import java.util.Map;
import java.util.ArrayList;
import edu.northeastern.cs6510.selfcheckout.config.SelfCheckoutProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InventoryStore {
    private final JdbcTemplate jdbcTemplate;
    private final SelfCheckoutProperties properties;

    public InventoryStore(JdbcTemplate jdbcTemplate, SelfCheckoutProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public Optional<SkuInventory> findBySku(String sku) {
        return jdbcTemplate.query("SELECT sku, initial_stock, stock, reserved, low_stock_since FROM inventory WHERE sku = ?",
                (rows, rowNumber) -> new SkuInventory(rows.getString("sku"), rows.getInt("initial_stock"),
                        rows.getInt("stock"), rows.getInt("reserved"),
                        rows.getTimestamp("low_stock_since") == null ? null : rows.getTimestamp("low_stock_since").toInstant()),
                sku).stream().findFirst();
    }

    @Transactional
    public boolean tryClaimOne(String transactionId, String sku) {
        int updated = jdbcTemplate.update(
                "UPDATE inventory SET reserved = reserved + 1 WHERE sku = ? AND stock - reserved > 0", sku);
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update("INSERT INTO inventory_claims (transaction_id, sku, quantity) VALUES (?, ?, 1) "
                        + "ON DUPLICATE KEY UPDATE quantity = quantity + 1", transactionId, sku);
        return true;
    }

    @Transactional
    public void releaseClaims(String transactionId, String sku) {
        Integer quantity = jdbcTemplate.query("SELECT quantity FROM inventory_claims WHERE transaction_id = ? AND sku = ?",
                resultSet -> resultSet.next() ? resultSet.getInt(1) : null, transactionId, sku);
        if (quantity == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM inventory_claims WHERE transaction_id = ? AND sku = ?", transactionId, sku);
        jdbcTemplate.update("UPDATE inventory SET reserved = reserved - ? WHERE sku = ?", quantity, sku);
    }

    @Transactional
    public void applyCompletion(String transactionId, Map<String, Integer> scannedQty, Map<String, Integer> claimedQty) {
        for (String sku : scannedQty.keySet().stream().sorted().toList()) {
            int quantity = scannedQty.get(sku);
            if (claimedQty.get(sku) == null || quantity != claimedQty.get(sku)) {
                throw new IllegalStateException("transaction contains an unclaimed unit");
            }
            Map<String, Object> locked = jdbcTemplate.queryForMap("SELECT stock, reserved FROM inventory WHERE sku = ? FOR UPDATE", sku);
            int stock = ((Number) locked.get("stock")).intValue();
            int reserved = ((Number) locked.get("reserved")).intValue();
            if (stock < quantity || reserved < quantity) {
                throw new IllegalStateException("inventory invariant violated at completion");
            }
            int resultingStock = stock - quantity;
            jdbcTemplate.update("UPDATE inventory SET stock = ?, reserved = reserved - ?, low_stock_since = CASE "
                            + "WHEN ? < ? AND low_stock_since IS NULL THEN CURRENT_TIMESTAMP(6) "
                            + "WHEN ? >= ? THEN NULL ELSE low_stock_since END WHERE sku = ?",
                    resultingStock, quantity, resultingStock, properties.lowStockThreshold(), resultingStock,
                    properties.lowStockThreshold(), sku);
            jdbcTemplate.update("INSERT INTO inventory_movements (sku, transaction_id, quantity_delta, stock_after, occurred_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
                    sku, transactionId, -quantity, resultingStock);
        }
        jdbcTemplate.update("DELETE FROM inventory_claims WHERE transaction_id = ?", transactionId);
    }

    @Transactional
    public void releaseAllClaims(String transactionId) {
        var claims = jdbcTemplate.query("SELECT sku, quantity FROM inventory_claims WHERE transaction_id = ? ORDER BY sku FOR UPDATE",
                (rs, row) -> Map.entry(rs.getString("sku"), rs.getInt("quantity")), transactionId);
        for (var claim : claims) {
            jdbcTemplate.update("UPDATE inventory SET reserved = reserved - ? WHERE sku = ?", claim.getValue(), claim.getKey());
        }
        jdbcTemplate.update("DELETE FROM inventory_claims WHERE transaction_id = ?", transactionId);
    }
}
