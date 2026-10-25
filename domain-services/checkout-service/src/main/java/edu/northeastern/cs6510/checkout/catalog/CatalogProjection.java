package edu.northeastern.cs6510.checkout.catalog;

import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductV1;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checkout's local, versioned copy of Catalog's active products (research R6). Scans read the
 * in-memory map; {@code catalog_projection} keeps it across restarts. The projection is derived
 * data: deleting its rows forces a full reload, and it is never authoritative.
 */
@Component
public class CatalogProjection {
  public enum State { EMPTY, CURRENT, STALE }
  public record ProjectedProduct(String name, long priceCents) { }

  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final Duration maxStaleness;
  private final Clock clock;

  private volatile Map<String, ProjectedProduct> products = Map.of();
  private volatile Long loadedVersion;
  private volatile Instant lastVerifiedAt;

  @Autowired
  public CatalogProjection(JdbcTemplate db, PlatformTransactionManager transactionManager,
                           @Value("${catalog.projection.max-staleness:60s}") Duration maxStaleness) {
    this(db, transactionManager, maxStaleness, Clock.systemUTC());
  }

  CatalogProjection(JdbcTemplate db, PlatformTransactionManager transactionManager, Duration maxStaleness, Clock clock) {
    this.db = db;
    this.transactions = new TransactionTemplate(transactionManager);
    this.maxStaleness = maxStaleness;
    this.clock = clock;
  }

  /** Restores the projection persisted by a previous process. */
  @PostConstruct
  void loadFromStore() {
    db.query("SELECT catalog_version, last_verified_at FROM projection_state WHERE id = 1", rs -> {
      long version = rs.getLong(1);
      loadedVersion = rs.wasNull() ? null : version;
      Timestamp verified = rs.getTimestamp(2);
      lastVerifiedAt = verified == null ? null : verified.toInstant();
    });
    Map<String, ProjectedProduct> rows = new HashMap<>();
    db.query("SELECT sku, name, price_cents FROM catalog_projection",
        rs -> { rows.put(rs.getString(1), new ProjectedProduct(rs.getString(2), rs.getLong(3))); });
    products = Map.copyOf(rows);
  }

  public State state() {
    if (loadedVersion == null) return State.EMPTY;
    Instant verified = lastVerifiedAt;
    if (verified == null || Duration.between(verified, clock.instant()).compareTo(maxStaleness) > 0) return State.STALE;
    return State.CURRENT;
  }

  public Optional<ProjectedProduct> find(String sku) { return Optional.ofNullable(products.get(sku)); }

  public Long loadedVersion() { return loadedVersion; }

  public Instant lastVerifiedAt() { return lastVerifiedAt; }

  public int storedRowCount() {
    Integer count = db.queryForObject("SELECT COUNT(*) FROM catalog_projection", Integer.class);
    return count == null ? 0 : count;
  }

  /** Adds one product confirmed by a direct Catalog lookup (stale or empty miss path). */
  public synchronized void upsertSingle(ProductV1 product) {
    db.update("INSERT INTO catalog_projection (sku, name, price_cents, catalog_version) VALUES (?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE name = VALUES(name), price_cents = VALUES(price_cents), catalog_version = VALUES(catalog_version)",
        product.sku(), product.name(), product.priceCents(), product.catalogVersion());
    Map<String, ProjectedProduct> next = new HashMap<>(products);
    next.put(product.sku(), new ProjectedProduct(product.name(), product.priceCents()));
    products = Map.copyOf(next);
  }

  /** Atomically replaces the projection with a complete load taken at a single catalog version. */
  public synchronized void replaceAll(long version, List<ProductPageV1.Item> items) {
    Instant now = clock.instant();
    transactions.executeWithoutResult(status -> {
      db.update("DELETE FROM catalog_projection");
      db.batchUpdate("INSERT INTO catalog_projection (sku, name, price_cents, catalog_version) VALUES (?, ?, ?, ?)",
          items, 500, (ps, item) -> {
            ps.setString(1, item.sku());
            ps.setString(2, item.name());
            ps.setLong(3, item.priceCents());
            ps.setLong(4, version);
          });
      db.update("UPDATE projection_state SET catalog_version = ?, loaded_at = ?, last_verified_at = ? WHERE id = 1",
          version, Timestamp.from(now), Timestamp.from(now));
    });
    Map<String, ProjectedProduct> next = new HashMap<>();
    for (ProductPageV1.Item item : items) next.put(item.sku(), new ProjectedProduct(item.name(), item.priceCents()));
    products = Map.copyOf(next);
    loadedVersion = version;
    lastVerifiedAt = now;
  }

  /** Records a successful version check that found the loaded version still current. */
  public void markVerified() {
    Instant now = clock.instant();
    db.update("UPDATE projection_state SET last_verified_at = ? WHERE id = 1", Timestamp.from(now));
    lastVerifiedAt = now;
  }
}
