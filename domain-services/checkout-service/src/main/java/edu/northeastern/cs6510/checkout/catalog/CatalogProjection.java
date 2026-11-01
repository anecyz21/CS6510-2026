package edu.northeastern.cs6510.checkout.catalog;

import edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checkout's local copy of Catalog's products, built from {@code catalog.products.v1} (005 research
 * R3). Scans read the in-memory map; {@code catalog_projection} keeps it across restarts, and
 * {@code projection_state.topic_offset} records the last applied record in the same transaction as
 * the rows, so a restart resumes exactly where it stopped. The copy is derived data: a rebuild
 * deletes it and replays the topic from the beginning.
 */
@Component
public class CatalogProjection {
  /** REPLAYING until the end offset captured at assignment is reached and a product is held. */
  public enum State { REPLAYING, CURRENT }
  public record ProjectedProduct(String name, long priceCents, long catalogVersion) { }

  private final JdbcTemplate db;
  private final TransactionTemplate transactions;

  private volatile Map<String, ProjectedProduct> products = Map.of();
  private volatile Long topicOffset;
  private volatile Long catalogVersion;
  /** End offset of the topic when the listener was assigned it; -1 while unknown. */
  private volatile long catchUpTarget = -1;

  public CatalogProjection(JdbcTemplate db, PlatformTransactionManager transactionManager) {
    this.db = db;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Restores the copy persisted by a previous process. */
  @PostConstruct
  void loadFromStore() {
    db.query("SELECT catalog_version, topic_offset FROM projection_state WHERE id = 1", rs -> {
      long version = rs.getLong(1);
      catalogVersion = rs.wasNull() ? null : version;
      long offset = rs.getLong(2);
      topicOffset = rs.wasNull() ? null : offset;
    });
    Map<String, ProjectedProduct> rows = new HashMap<>();
    db.query("SELECT sku, name, price_cents, catalog_version FROM catalog_projection",
        rs -> { rows.put(rs.getString(1), new ProjectedProduct(rs.getString(2), rs.getLong(3), rs.getLong(4))); });
    products = Map.copyOf(rows);
  }

  public Optional<ProjectedProduct> find(String sku) { return Optional.ofNullable(products.get(sku)); }

  /** Caught up with the topic as it was at assignment, and holding at least one product. */
  public boolean caughtUp() {
    long target = catchUpTarget;
    if (target < 0 || products.isEmpty()) return false;
    Long offset = topicOffset;
    return (offset == null ? 0 : offset + 1) >= target;
  }

  public State state() { return caughtUp() ? State.CURRENT : State.REPLAYING; }

  public Long topicOffset() { return topicOffset; }

  public Long catalogVersion() { return catalogVersion; }

  public long catchUpTarget() { return catchUpTarget; }

  public int size() { return products.size(); }

  void catchUpTo(long endOffset) { catchUpTarget = endOffset; }

  /**
   * Applies one batch in one local transaction and records {@code lastOffset}, the offset of the
   * batch's last record (valid or not). An event is applied only if its version is not older than
   * the stored one for that SKU; {@code active=false} deletes the SKU.
   */
  public synchronized void apply(List<ProductEventV1> events, long lastOffset) {
    Map<String, ProjectedProduct> next = new HashMap<>(products);
    List<Object[]> upserts = new ArrayList<>();
    List<Object[]> deletes = new ArrayList<>();
    long highest = catalogVersion == null ? 0 : catalogVersion;
    for (ProductEventV1 event : events) {
      highest = Math.max(highest, event.catalogVersion());
      ProjectedProduct stored = next.get(event.sku());
      if (stored != null && stored.catalogVersion() > event.catalogVersion()) continue;
      if (event.active()) {
        next.put(event.sku(), new ProjectedProduct(event.name(), event.priceCents(), event.catalogVersion()));
        upserts.add(new Object[] {event.sku(), event.name(), event.priceCents(), event.catalogVersion()});
      } else if (stored != null) {
        next.remove(event.sku());
        deletes.add(new Object[] {event.sku()});
      }
    }
    long version = highest;
    transactions.executeWithoutResult(status -> {
      if (!upserts.isEmpty()) {
        db.batchUpdate("INSERT INTO catalog_projection (sku, name, price_cents, catalog_version) VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE name = VALUES(name), price_cents = VALUES(price_cents), catalog_version = VALUES(catalog_version)",
            upserts);
      }
      if (!deletes.isEmpty()) db.batchUpdate("DELETE FROM catalog_projection WHERE sku = ?", deletes);
      db.update("UPDATE projection_state SET topic_offset = ?, catalog_version = ?, loaded_at = ? WHERE id = 1",
          lastOffset, version == 0 ? null : version, Timestamp.from(Instant.now()));
    });
    products = Map.copyOf(next);
    topicOffset = lastOffset;
    catalogVersion = version == 0 ? null : version;
  }

  /**
   * Forgets the stored position (keeping the rows) so the next assignment replays from the
   * beginning: used when the stored offset is past the topic's end, e.g. after a broker reset.
   */
  public synchronized void forgetPosition() {
    db.update("UPDATE projection_state SET topic_offset = NULL WHERE id = 1");
    topicOffset = null;
  }

  /** Deletes the copy and its position in one transaction (operator rebuild); the listener then replays. */
  public synchronized void clear() {
    transactions.executeWithoutResult(status -> {
      db.update("DELETE FROM catalog_projection");
      db.update("UPDATE projection_state SET topic_offset = NULL, catalog_version = NULL, loaded_at = NULL WHERE id = 1");
    });
    products = Map.of();
    topicOffset = null;
    catalogVersion = null;
    catchUpTarget = -1;
  }
}
