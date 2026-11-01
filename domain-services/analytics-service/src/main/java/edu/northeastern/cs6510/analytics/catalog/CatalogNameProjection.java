package edu.northeastern.cs6510.analytics.catalog;

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
 * Local copy of Catalog's product names, built from {@code catalog.products.v1} (005 research R3), so
 * read views show real names without asking Catalog. {@code projection_state.topic_offset} records
 * the last applied record in the same transaction as the rows. Derived data: a rebuild deletes it
 * and replays the topic from the beginning.
 */
@Component
public class CatalogNameProjection {
  /** REPLAYING until the end offset captured at assignment is reached and a name is held. */
  public enum State { REPLAYING, CURRENT }
  private record Named(String name, long catalogVersion) { }

  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private volatile Map<String, Named> names = Map.of();
  private volatile Long topicOffset;
  private volatile Long catalogVersion;
  /** End offset of the topic when the listener was assigned it; -1 while unknown. */
  private volatile long catchUpTarget = -1;

  public CatalogNameProjection(JdbcTemplate db, PlatformTransactionManager transactionManager) {
    this.db = db;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @PostConstruct
  void loadFromStore() {
    db.query("SELECT catalog_version, topic_offset FROM projection_state WHERE id = 1", rs -> {
      long version = rs.getLong(1);
      catalogVersion = rs.wasNull() ? null : version;
      long offset = rs.getLong(2);
      topicOffset = rs.wasNull() ? null : offset;
    });
    Map<String, Named> rows = new HashMap<>();
    db.query("SELECT sku, name, catalog_version FROM catalog_names", rs -> { rows.put(rs.getString(1), new Named(rs.getString(2), rs.getLong(3))); });
    names = Map.copyOf(rows);
  }

  /** The product name, or empty when the copy does not hold the SKU. Never falls back to the SKU (finding F4). */
  public Optional<String> name(String sku) {
    Named named = names.get(sku);
    return named == null ? Optional.empty() : Optional.of(named.name());
  }

  /** Caught up with the topic as it was at assignment, and holding at least one name. */
  public boolean caughtUp() {
    long target = catchUpTarget;
    if (target < 0 || names.isEmpty()) return false;
    Long offset = topicOffset;
    return (offset == null ? 0 : offset + 1) >= target;
  }

  public State state() { return caughtUp() ? State.CURRENT : State.REPLAYING; }

  public Long topicOffset() { return topicOffset; }

  public Long catalogVersion() { return catalogVersion; }

  public long catchUpTarget() { return catchUpTarget; }

  public int size() { return names.size(); }

  void catchUpTo(long endOffset) { catchUpTarget = endOffset; }

  /**
   * Applies one batch in one local transaction and records {@code lastOffset}, the offset of the
   * batch's last record. An event is applied only if its version is not older than the stored one
   * for that SKU; {@code active=false} deletes the SKU.
   */
  public synchronized void apply(List<ProductEventV1> events, long lastOffset) {
    Map<String, Named> next = new HashMap<>(names);
    List<Object[]> upserts = new ArrayList<>();
    List<Object[]> deletes = new ArrayList<>();
    long highest = catalogVersion == null ? 0 : catalogVersion;
    for (ProductEventV1 event : events) {
      highest = Math.max(highest, event.catalogVersion());
      Named stored = next.get(event.sku());
      if (stored != null && stored.catalogVersion() > event.catalogVersion()) continue;
      if (event.active()) {
        next.put(event.sku(), new Named(event.name(), event.catalogVersion()));
        upserts.add(new Object[] {event.sku(), event.name(), event.catalogVersion()});
      } else if (stored != null) {
        next.remove(event.sku());
        deletes.add(new Object[] {event.sku()});
      }
    }
    long version = highest;
    transactions.executeWithoutResult(status -> {
      if (!upserts.isEmpty()) {
        db.batchUpdate("INSERT INTO catalog_names (sku, name, catalog_version) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE name = VALUES(name), catalog_version = VALUES(catalog_version)", upserts);
      }
      if (!deletes.isEmpty()) db.batchUpdate("DELETE FROM catalog_names WHERE sku = ?", deletes);
      db.update("UPDATE projection_state SET topic_offset = ?, catalog_version = ?, loaded_at = ? WHERE id = 1",
          lastOffset, version == 0 ? null : version, Timestamp.from(Instant.now()));
    });
    names = Map.copyOf(next);
    topicOffset = lastOffset;
    catalogVersion = version == 0 ? null : version;
  }

  /** Forgets the stored position (keeping the rows) so the next assignment replays from the beginning. */
  public synchronized void forgetPosition() {
    db.update("UPDATE projection_state SET topic_offset = NULL WHERE id = 1");
    topicOffset = null;
  }

  /** Deletes the copy and its position in one transaction (operator rebuild); the listener then replays. */
  public synchronized void clear() {
    transactions.executeWithoutResult(status -> {
      db.update("DELETE FROM catalog_names");
      db.update("UPDATE projection_state SET topic_offset = NULL, catalog_version = NULL, loaded_at = NULL WHERE id = 1");
    });
    names = Map.of();
    topicOffset = null;
    catalogVersion = null;
    catchUpTarget = -1;
  }
}
