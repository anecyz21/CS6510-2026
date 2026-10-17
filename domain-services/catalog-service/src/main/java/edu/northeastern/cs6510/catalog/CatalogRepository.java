package edu.northeastern.cs6510.catalog;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Catalog-owned persistence gateway. No other service receives this repository. */
@Repository
public class CatalogRepository {
  private final JdbcTemplate db;
  public CatalogRepository(JdbcTemplate db) { this.db = db; }
  public List<CatalogItem> activeItems() {
    return db.query("SELECT sku,name,price_cents FROM catalog_items WHERE active=TRUE ORDER BY sku",
        (rs, row) -> new CatalogItem(rs.getString(1), rs.getString(2), rs.getLong(3)));
  }
  public record CatalogItem(String sku, String name, long priceCents) {
    public CatalogItem {
      if (sku == null || sku.isBlank() || sku.length() > 64) throw new IllegalArgumentException("sku must be nonblank and at most 64 characters");
      if (name == null || name.isBlank() || name.length() > 255) throw new IllegalArgumentException("name must be nonblank and at most 255 characters");
      if (priceCents < 0) throw new IllegalArgumentException("price must be non-negative");
    }
  }
}
