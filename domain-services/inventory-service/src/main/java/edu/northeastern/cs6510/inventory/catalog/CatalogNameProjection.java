package edu.northeastern.cs6510.inventory.catalog;

import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local, versioned copy of Catalog's product names (research R7), used so read views show real
 * names without calling Catalog per request. Derived data: deleting its rows forces a reload.
 */
@Component
public class CatalogNameProjection {
  public enum State { EMPTY, CURRENT, STALE }

  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final Duration maxStaleness;
  private final Clock clock = Clock.systemUTC();
  private volatile Map<String, String> names = Map.of();
  private volatile Long loadedVersion;
  private volatile Instant lastVerifiedAt;

  public CatalogNameProjection(JdbcTemplate db, PlatformTransactionManager transactionManager,
                               @Value("${catalog.projection.max-staleness:60s}") Duration maxStaleness) {
    this.db = db;
    this.transactions = new TransactionTemplate(transactionManager);
    this.maxStaleness = maxStaleness;
  }

  @PostConstruct
  void loadFromStore() {
    db.query("SELECT catalog_version, last_verified_at FROM projection_state WHERE id = 1", rs -> {
      long version = rs.getLong(1);
      loadedVersion = rs.wasNull() ? null : version;
      Timestamp verified = rs.getTimestamp(2);
      lastVerifiedAt = verified == null ? null : verified.toInstant();
    });
    Map<String, String> rows = new HashMap<>();
    db.query("SELECT sku, name FROM catalog_names", rs -> { rows.put(rs.getString(1), rs.getString(2)); });
    names = Map.copyOf(rows);
  }

  public State state() {
    if (loadedVersion == null) return State.EMPTY;
    Instant verified = lastVerifiedAt;
    if (verified == null || Duration.between(verified, clock.instant()).compareTo(maxStaleness) > 0) return State.STALE;
    return State.CURRENT;
  }

  /** The product name, or empty when the projection does not know the SKU. Never falls back to the SKU (finding F4). */
  public Optional<String> name(String sku) { return Optional.ofNullable(names.get(sku)); }

  public Long loadedVersion() { return loadedVersion; }

  public Instant lastVerifiedAt() { return lastVerifiedAt; }

  public int storedRowCount() {
    Integer count = db.queryForObject("SELECT COUNT(*) FROM catalog_names", Integer.class);
    return count == null ? 0 : count;
  }

  /** Atomically replaces every name with a complete load taken at one catalog version. */
  public synchronized void replaceAll(long version, List<ProductPageV1.Item> items) {
    Instant now = clock.instant();
    transactions.executeWithoutResult(status -> {
      db.update("DELETE FROM catalog_names");
      db.batchUpdate("INSERT INTO catalog_names (sku, name, catalog_version) VALUES (?, ?, ?)", items, 500, (ps, item) -> {
        ps.setString(1, item.sku());
        ps.setString(2, item.name());
        ps.setLong(3, version);
      });
      db.update("UPDATE projection_state SET catalog_version = ?, loaded_at = ?, last_verified_at = ? WHERE id = 1",
          version, Timestamp.from(now), Timestamp.from(now));
    });
    Map<String, String> next = new HashMap<>();
    for (ProductPageV1.Item item : items) next.put(item.sku(), item.name());
    names = Map.copyOf(next);
    loadedVersion = version;
    lastVerifiedAt = now;
  }

  /**
   * Forgets every name after the stored rows were deleted, so views stop serving names the store no
   * longer holds; the loader then reloads from Catalog when it can.
   */
  public synchronized void clear() {
    db.update("UPDATE projection_state SET catalog_version = NULL, loaded_at = NULL, last_verified_at = NULL WHERE id = 1");
    names = Map.of();
    loadedVersion = null;
    lastVerifiedAt = null;
  }

  public void markVerified() {
    Instant now = clock.instant();
    db.update("UPDATE projection_state SET last_verified_at = ? WHERE id = 1", Timestamp.from(now));
    lastVerifiedAt = now;
  }
}
