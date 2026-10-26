package edu.northeastern.cs6510.inventory.reservation;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies committed sales to stock in batches. A reservation commit only queues its lines in
 * {@code sale_queue}; this moves each SKU's queued quantity from reserved to physical stock with one
 * update per SKU per batch, writes one SALE movement per queued line and deletes the queue rows, all
 * in one local transaction. Many completions of a hot SKU therefore cost one row lock and one redo
 * flush instead of one each.
 *
 * <p>Until a line is applied it stays in {@code reserved_stock}, so availability (physical minus
 * reserved) is always exact; only the physical figure, as shown by the low-stock view, lags by up to
 * one interval. {@code SKIP LOCKED} lets several instances share the queue without applying a row
 * twice.
 */
@Component
public class SaleApplier {
  private static final Logger log = LoggerFactory.getLogger(SaleApplier.class);
  static final int BATCH = 2000;

  private final JdbcTemplate db;
  private final ReservationRepository repository;
  private final TransactionTemplate transactions;

  public SaleApplier(JdbcTemplate db, ReservationRepository repository, PlatformTransactionManager transactionManager) {
    this.db = db;
    this.repository = repository;
    this.transactions = new TransactionTemplate(transactionManager);
    // READ COMMITTED: the queue scan then locks only the rows it returns, with no gap lock at the
    // end of the index that would make every commit's queue insert wait for this batch.
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private record Queued(long queueId, String attemptId, String sku, int quantity, Timestamp committedAt) { }

  @Scheduled(initialDelay = 1000, fixedDelayString = "${inventory.sales.apply-interval:100ms}")
  public void applyPending() {
    try {
      while (applyOnce() == BATCH) {
        // a full batch means more may be waiting
      }
    } catch (RuntimeException e) {
      log.warn("applying committed sales failed, retrying on the next interval: {}", e.toString());
    }
  }

  /** Applies up to {@value #BATCH} queued lines in queue order; returns how many were applied. */
  public int applyOnce() {
    Integer applied = transactions.execute(status -> {
      List<Queued> rows = db.query(
          "SELECT queue_id, attempt_id, sku, quantity, committed_at FROM sale_queue ORDER BY queue_id LIMIT ? FOR UPDATE SKIP LOCKED",
          (rs, n) -> new Queued(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5)), BATCH);
      if (rows.isEmpty()) return 0;
      // Positions are updated in the same descending SKU order the reservation commands use.
      Map<String, List<Queued>> bySku = new TreeMap<>(Comparator.reverseOrder());
      for (Queued row : rows) bySku.computeIfAbsent(row.sku(), sku -> new ArrayList<>()).add(row);
      List<Object[]> movements = new ArrayList<>(rows.size());
      for (Map.Entry<String, List<Queued>> sku : bySku.entrySet()) {
        int total = sku.getValue().stream().mapToInt(Queued::quantity).sum();
        int after = repository.applySale(sku.getKey(), total).orElseThrow(() -> new IllegalStateException(
            "reserved stock for " + sku.getKey() + " is lower than its committed sales (" + total + ")"));
        int physical = after + total;   // walk the batch in commit order so each movement has its own physical_after
        for (Queued row : sku.getValue()) {
          physical -= row.quantity();
          movements.add(new Object[] {row.sku(), row.attemptId(), -row.quantity(), physical, row.committedAt()});
        }
      }
      db.batchUpdate("INSERT INTO inventory_movements (sku, attempt_id, movement_type, quantity_delta, physical_after, occurred_at) "
          + "VALUES (?, ?, 'SALE', ?, ?, ?)", movements);
      String ids = rows.stream().map(r -> String.valueOf(r.queueId())).collect(Collectors.joining(","));
      db.update("DELETE FROM sale_queue WHERE queue_id IN (" + ids + ")");
      return rows.size();
    });
    return applied == null ? 0 : applied;
  }

  /** Committed lines not yet applied to stock. */
  public long pending() {
    Long count = db.queryForObject("SELECT COUNT(*) FROM sale_queue", Long.class);
    return count == null ? 0 : count;
  }

  /** For tests and diagnostics: applies until the queue is empty. */
  public void drain() {
    while (applyOnce() > 0) {
      // keep applying
    }
  }
}
