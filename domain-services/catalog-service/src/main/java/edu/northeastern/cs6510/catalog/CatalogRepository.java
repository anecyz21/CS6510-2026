package edu.northeastern.cs6510.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

  /** Up to {@code limit} active items with a SKU greater than {@code afterSku} (null = from the start), in SKU order. */
  public List<CatalogItem> activeItemsAfter(String afterSku, int limit) {
    return db.query("SELECT sku,name,price_cents FROM catalog_items WHERE active=TRUE AND sku > ? ORDER BY sku LIMIT ?",
        (rs, row) -> new CatalogItem(rs.getString(1), rs.getString(2), rs.getLong(3)), afterSku == null ? "" : afterSku, limit);
  }

  public Optional<CatalogItem> activeItem(String sku) {
    return db.query("SELECT sku,name,price_cents FROM catalog_items WHERE active=TRUE AND sku = ?",
        (rs, row) -> new CatalogItem(rs.getString(1), rs.getString(2), rs.getLong(3)), sku).stream().findFirst();
  }

  public CatalogVersion version() {
    return db.queryForObject(
        "SELECT m.catalog_version, m.updated_at, (SELECT COUNT(*) FROM catalog_items WHERE active=TRUE) FROM catalog_metadata m WHERE m.id = 1",
        (rs, row) -> new CatalogVersion(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getInt(3)));
  }

  public record CatalogVersion(long catalogVersion, Instant updatedAt, int activeItemCount) { }

  public record CatalogItem(String sku, String name, long priceCents) {
    public CatalogItem {
      if (sku == null || sku.isBlank() || sku.length() > 64) throw new IllegalArgumentException("sku must be nonblank and at most 64 characters");
      if (name == null || name.isBlank() || name.length() > 255) throw new IllegalArgumentException("name must be nonblank and at most 255 characters");
      if (priceCents < 0) throw new IllegalArgumentException("price must be non-negative");
    }
  }
}
