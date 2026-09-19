package edu.northeastern.cs6510.selfcheckout.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final JdbcTemplate jdbcTemplate;
    public CatalogService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public List<CatalogItem> findAll() {
        return jdbcTemplate.query("SELECT sku, name, price_cents FROM catalog_items ORDER BY sku",
                (rs, row) -> new CatalogItem(rs.getString("sku"), rs.getString("name"), rs.getLong("price_cents")));
    }

    public Optional<CatalogItem> findBySku(String sku) {
        return jdbcTemplate.query("SELECT sku, name, price_cents FROM catalog_items WHERE sku = ?",
                (rs, row) -> new CatalogItem(rs.getString("sku"), rs.getString("name"), rs.getLong("price_cents")), sku)
                .stream().findFirst();
    }
}
