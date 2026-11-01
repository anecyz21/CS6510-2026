package edu.northeastern.cs6510.catalog;

import java.sql.Timestamp;
import java.time.Instant;
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

  /** Every item, for sale or not, in SKU order: one catalog.products.v1 record each (005 research R2). */
  public List<PublishedItem> allItems() {
    return db.query("SELECT sku,name,price_cents,active FROM catalog_items ORDER BY sku",
        (rs, row) -> new PublishedItem(new CatalogItem(rs.getString(1), rs.getString(2), rs.getLong(3)), rs.getBoolean(4)));
  }

  public long catalogVersion() {
    Long version = db.queryForObject("SELECT catalog_version FROM catalog_metadata WHERE id = 1", Long.class);
    return version == null ? 0 : version;
  }

  /** The last version every item was acknowledged for, or null when nothing was ever published. */
  public Long publishedVersion() {
    return db.queryForObject("SELECT published_version FROM catalog_publication WHERE id = 1", Long.class);
  }

  public void markPublished(long version, Instant at) {
    db.update("UPDATE catalog_publication SET published_version = ?, published_at = ? WHERE id = 1", version, Timestamp.from(at));
  }

  /** Makes the next publisher pass republish every item (operator republish). */
  public void resetPublication() {
    db.update("UPDATE catalog_publication SET published_version = NULL, published_at = NULL WHERE id = 1");
  }

  public record PublishedItem(CatalogItem item, boolean active) { }

  public record CatalogItem(String sku, String name, long priceCents) {
    public CatalogItem {
      if (sku == null || sku.isBlank() || sku.length() > 64) throw new IllegalArgumentException("sku must be nonblank and at most 64 characters");
      if (name == null || name.isBlank() || name.length() > 255) throw new IllegalArgumentException("name must be nonblank and at most 255 characters");
      if (priceCents < 0) throw new IllegalArgumentException("price must be non-negative");
    }
  }
}
