package edu.northeastern.cs6510.checkout.scan;

import edu.northeastern.cs6510.checkout.CheckoutRepository;
import edu.northeastern.cs6510.checkout.api.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts one scanned unit (FR-007, FR-008). The product is resolved before any transaction is
 * opened, so no remote call ever runs while a row lock is held. The basket upsert, the accepted-scan
 * record and the running totals then commit together in one short local transaction. A scan never
 * reserves or checks stock.
 */
@Service
public class ScanService {
  private static final int MAX_SCAN_ATTEMPTS = 8;

  private final ProductLookup products;
  private final CheckoutRepository transactionsRepository;
  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final Counter scanRetries;

  public ScanService(ProductLookup products, CheckoutRepository transactionsRepository, JdbcTemplate db,
                     PlatformTransactionManager transactionManager, MeterRegistry meters) {
    this.products = products;
    this.transactionsRepository = transactionsRepository;
    this.db = db;
    this.transactions = new TransactionTemplate(transactionManager);
    this.scanRetries = Counter.builder("checkout.db.retries").tag("operation", "scan").register(meters);
  }

  public ScanResult scan(String transactionId, String sku) {
    if (sku == null || sku.isBlank()) throw ApiException.missingSku();
    ProductLookup.Result product = products.lookup(sku);
    if (!(product instanceof ProductLookup.Accept accepted)) {
      // Transaction errors take precedence over SKU outcomes (public-api-amendment precedence).
      transactionsRepository.requireOpen(transactionId);
      throw product instanceof ProductLookup.UnknownSku ? ApiException.skuNotFound(sku) : ApiException.serviceUnavailable("catalog");
    }
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(status -> scanOnce(transactionId, sku, accepted));
      } catch (PessimisticLockingFailureException lockVictim) {
        if (attempt == MAX_SCAN_ATTEMPTS) throw lockVictim;
        scanRetries.increment();
        try {
          Thread.sleep(Math.min(250L, 15L << (attempt - 1)));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw lockVictim;
        }
      }
    }
  }

  private ScanResult scanOnce(String transactionId, String sku, ProductLookup.Accept product) {
    // FOR SHARE serializes with the completion freeze (FOR UPDATE): a scan either commits before
    // the freeze and is in the snapshot, or sees the busy flag (research R10).
    String[] locked = db.query("SELECT status, active_attempt_id FROM checkout_transactions WHERE transaction_id = ? FOR SHARE",
        rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null, transactionId);
    if (locked == null) throw ApiException.transactionNotFound(transactionId);
    if (!"OPEN".equals(locked[0])) throw ApiException.transactionNotOpen(transactionId);
    // Rejected immediately: not queued, no basket change, no accepted-scan record (FR-011).
    if (locked[1] != null) throw ApiException.transactionBusy(transactionId);
    // The price and name are captured at the first scan of a SKU and never re-priced.
    db.update("INSERT INTO basket_lines (transaction_id, sku, quantity, unit_price_cents, name) VALUES (?, ?, 1, ?, ?) "
        + "ON DUPLICATE KEY UPDATE quantity = quantity + 1", transactionId, sku, product.priceCents(), product.name());
    db.update("INSERT INTO accepted_scan_events (transaction_id, sku) VALUES (?, ?)", transactionId, sku);
    return db.queryForObject(
        "SELECT MAX(CASE WHEN sku = ? THEN unit_price_cents END), MAX(CASE WHEN sku = ? THEN name END), "
            + "COALESCE(SUM(quantity), 0), COALESCE(SUM(quantity * unit_price_cents), 0) FROM basket_lines WHERE transaction_id = ?",
        (rs, row) -> new ScanResult(transactionId, sku, rs.getString(2), rs.getLong(1), rs.getInt(3), rs.getLong(4)),
        sku, sku, transactionId);
  }

  public record ScanResult(String transactionId, String sku, String name, long unitPriceCents,
                           int itemCount, long runningTotalCents) { }
}
