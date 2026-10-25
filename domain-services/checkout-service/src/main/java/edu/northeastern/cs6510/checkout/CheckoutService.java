package edu.northeastern.cs6510.checkout;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.CannotAcquireLockException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Collectors;

@Service
public class CheckoutService {
  private final CheckoutRepository repository;
  private final TransactionTemplate transactions;
  private final Counter scanRetries;
  private static final int MAX_SCAN_ATTEMPTS = 8;

  public CheckoutService(CheckoutRepository repository, PlatformTransactionManager transactionManager, MeterRegistry meters) {
    this.repository = repository;
    this.transactions = new TransactionTemplate(transactionManager);
    this.scanRetries = Counter.builder("checkout.db.retries").tag("operation", "scan").register(meters);
  }
  public CheckoutRepository.Transaction start(String stationId) { return repository.start(stationId); }
  public CheckoutRepository.Transaction get(String transactionId) { return repository.transaction(transactionId); }
  public CheckoutRepository.Totals basketTotals(String transactionId) { return repository.basketTotals(transactionId); }
  public CheckoutRepository.Totals receiptTotals(String transactionId) { return repository.receiptTotals(transactionId); }

  /** Atomically captures Catalog's current price, updates Checkout's basket, and appends its outbox event. */
  public ScanResult acceptScan(String transactionId, String sku) {
    for (int attempt = 1; attempt <= MAX_SCAN_ATTEMPTS; attempt++) {
      try {
        ScanResult result = transactions.execute(status -> acceptScanOnce(transactionId, sku));
        if (result != null) return result;
      } catch (CannotAcquireLockException deadlock) {
        if (attempt == MAX_SCAN_ATTEMPTS) throw deadlock;
        scanRetries.increment();
        try { Thread.sleep(Math.min(250L, 15L << (attempt - 1))); } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw deadlock;
        }
      }
    }
    throw new IllegalStateException("unreachable scan retry state");
  }

  private ScanResult acceptScanOnce(String transactionId, String sku) {
    if (!repository.isOpen(transactionId)) throw new IllegalStateException("transaction is not open");
    CheckoutRepository.CatalogItem item = repository.activeCatalogItem(sku)
        .orElseThrow(() -> new IllegalArgumentException("unknown or inactive SKU"));
    repository.addBasketUnit(transactionId, item.sku(), item.priceCents());
    repository.appendAcceptedScan(transactionId, item.sku());
    return new ScanResult(transactionId, item.sku(), item.name(), item.priceCents());
  }

  @Transactional
  public void complete(String transactionId) {
    var lines = repository.basketLines(transactionId);
    if (lines.isEmpty()) throw new IllegalStateException("basket is empty");
    String requested = lines.stream().map(line -> "{\"sku\":\"" + line.sku() + "\",\"quantity\":" + line.quantity() + "}").collect(Collectors.joining(",", "[", "]"));
    var results = repository.fulfill(transactionId, requested);
    repository.applyFulfillment(transactionId, results);
  }

  public record ScanResult(String transactionId, String sku, String name, long unitPriceCents) { }
}
