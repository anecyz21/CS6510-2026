package edu.northeastern.cs6510.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;

@SpringBootApplication
public class InventoryApplication {
    public static void main(String[] args) { SpringApplication.run(InventoryApplication.class, args); }
    /** Reports physical stock, so a reservation alone never appears as a decrement. Names arrive with the catalog projection (US4). */
    @RestController static class LowStock { private final JdbcTemplate db; LowStock(JdbcTemplate db){this.db=db;}
        @GetMapping("/inventory/low-stock") Map<String,Object> low(@RequestParam(defaultValue="10") int threshold){
            return Map.of("threshold", threshold, "generatedAt", Instant.now().toString(), "alerts", db.query("SELECT sku, physical_stock FROM inventory_positions WHERE physical_stock < ? ORDER BY sku", (rs,n)->Map.of("sku",rs.getString(1),"name",rs.getString(1),"currentStock",rs.getInt(2),"threshold",threshold,"triggeredAt",Instant.now().toString()), threshold));
        }
    }
}
