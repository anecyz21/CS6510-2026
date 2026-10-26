package edu.northeastern.cs6510.checkout.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prepare and finalize against a real MySQL with Checkout's own migrations: the one-statement
 * receipt intent, the set-based finalize, and the receipt built in memory equal to the stored one.
 */
@Testcontainers(disabledWithoutDocker = true)
class FinalizeIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("checkout");

  private static JdbcTemplate db;
  private static JdbcCompletionAttemptRepository attempts;
  private static ReceiptAssembler receipts;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(dataSource);
    attempts = new JdbcCompletionAttemptRepository(db);
    receipts = new ReceiptAssembler(db);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @Test
  void partialThenFullPurchaseReducesTheBasketAndCompletesOnlyWhenEmpty() {
    String tx = transaction(Map.of("SKU-A", 3, "SKU-B", 1, "SKU-C", 2));

    // First attempt: A partly, B fully, C not at all.
    CompletionAttempt first = finalizeWith(tx, Map.of("SKU-A", 2, "SKU-B", 1, "SKU-C", 0));
    assertEquals(Map.of("SKU-A", 1, "SKU-C", 2), basket(tx));
    assertEquals(List.of("SKU-A:2", "SKU-B:1"), receiptLines(first.attemptId()));
    assertEquals("OPEN", status(tx));
    assertNull(activeAttempt(tx), "the freeze flag is cleared");
    assertNull(db.queryForObject("SELECT lease_owner FROM completion_attempts WHERE attempt_id = ?", String.class, first.attemptId()),
        "the terminal attempt's lease is cleared with it");
    assertFalse(Boolean.TRUE.equals(transactions.execute(s -> attempts.finalizeAttempt(first, Instant.now()))), "a second finalize loses");

    // Second attempt buys everything left.
    CompletionAttempt second = finalizeWith(tx, Map.of("SKU-A", 1, "SKU-C", 2));
    assertEquals(Map.of(), basket(tx));
    assertEquals(List.of("SKU-A:1", "SKU-C:2"), receiptLines(second.attemptId()));
    assertEquals("COMPLETED", status(tx));
    assertTrue(db.queryForObject("SELECT completed_at IS NOT NULL FROM checkout_transactions WHERE transaction_id = ?", Boolean.class, tx));
  }

  @Test
  void receiptBuiltInMemoryEqualsTheStoredReceipt() {
    String tx = transaction(Map.of("SKU-D", 2, "SKU-E", 1));
    CompletionAttempt finalized = finalizeWith(tx, Map.of("SKU-D", 1, "SKU-E", 1));
    // Read the header the way the freeze lock does (getTimestamp().toInstant()).
    CompletionAttemptRepository.TransactionLock header = transactions.execute(s -> attempts.lockTransaction(tx).orElseThrow());
    Map<String, Object> fromMemory = receipts.receipt(finalized, header.stationId(), header.startedAt());
    assertEquals(receipts.receipt(finalized.attemptId()), fromMemory);
  }

  /** Freeze, prepare with {@code granted} and finalize, as the driver does; returns the finalized attempt. */
  private static CompletionAttempt finalizeWith(String tx, Map<String, Integer> granted) {
    String attemptId = UuidV7.next();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    transactions.executeWithoutResult(s -> attempts.insertAttemptWithLines(attemptId, tx, attempts.basketLines(tx), now, "test",
        now.plusSeconds(10)));
    transactions.executeWithoutResult(s -> {
      assertTrue(attempts.transition(attemptId, AttemptState.RESERVING, AttemptState.COMMITTING, now));
      attempts.storeReserved(attemptId, granted);
    });
    CompletionAttempt prepared = attempts.find(attemptId).orElseThrow();
    prepared.lines().forEach(l -> assertEquals(granted.getOrDefault(l.sku(), 0), l.reservedQuantity(), l.sku()));
    assertTrue(Boolean.TRUE.equals(transactions.execute(s -> attempts.finalizeAttempt(prepared, now))));
    return attempts.find(attemptId).orElseThrow();
  }

  private static String transaction(Map<String, Integer> lines) {
    String tx = UUID.randomUUID().toString();
    db.update("INSERT INTO checkout_transactions (transaction_id, station_id, status, started_at) VALUES (?, 'station-1', 'OPEN', ?)",
        tx, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
    lines.forEach((sku, quantity) -> db.update(
        "INSERT INTO basket_lines (transaction_id, sku, quantity, unit_price_cents, name) VALUES (?, ?, ?, 250, ?)",
        tx, sku, quantity, "Item " + sku));
    return tx;
  }

  private static Map<String, Integer> basket(String tx) {
    Map<String, Integer> out = new java.util.TreeMap<>();
    db.query("SELECT sku, quantity FROM basket_lines WHERE transaction_id = ?", rs -> { out.put(rs.getString(1), rs.getInt(2)); }, tx);
    return out;
  }

  private static List<String> receiptLines(String attemptId) {
    return db.query("SELECT sku, quantity FROM receipt_lines WHERE attempt_id = ? ORDER BY sku",
        (rs, n) -> rs.getString(1) + ":" + rs.getInt(2), attemptId);
  }

  private static String status(String tx) {
    return db.queryForObject("SELECT status FROM checkout_transactions WHERE transaction_id = ?", String.class, tx);
  }

  private static String activeAttempt(String tx) {
    return db.queryForObject("SELECT active_attempt_id FROM checkout_transactions WHERE transaction_id = ?", String.class, tx);
  }
}
