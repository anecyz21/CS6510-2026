package edu.northeastern.cs6510.selfcheckout.catalog;

import edu.northeastern.cs6510.selfcheckout.config.SelfCheckoutProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CatalogSeeder implements ApplicationRunner {
    private static final long PRICE_SEED = 7_513L;
    private final JdbcTemplate jdbcTemplate;
    private final SelfCheckoutProperties properties;

    public CatalogSeeder(JdbcTemplate jdbcTemplate, SelfCheckoutProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_items", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }

        List<Object[]> catalogRows = new ArrayList<>(properties.catalogSize());
        List<Object[]> inventoryRows = new ArrayList<>(properties.catalogSize());
        for (int index = 1; index <= properties.catalogSize(); index++) {
            String sku = "SKU-%06d".formatted(index);
            catalogRows.add(new Object[] {sku, "Item " + index, deterministicPriceCents(index)});
            inventoryRows.add(new Object[] {sku, properties.stockPerItem(), properties.stockPerItem()});
        }
        jdbcTemplate.batchUpdate("INSERT INTO catalog_items (sku, name, price_cents) VALUES (?, ?, ?)", catalogRows);
        jdbcTemplate.batchUpdate("INSERT INTO inventory (sku, initial_stock, stock) VALUES (?, ?, ?)", inventoryRows);
    }

    private long deterministicPriceCents(int index) {
        long mixed = (PRICE_SEED * 1_103_515_245L + index * 12_345L) & 0x7fff_ffffL;
        return 100L + (mixed % 9_900L);
    }
}
