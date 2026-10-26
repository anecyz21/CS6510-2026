package edu.northeastern.cs6510.inventory.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import edu.northeastern.cs6510.inventory.api.InternalApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Reserve and commit against a real MySQL 8.4 with Inventory's own migrations (T041). */
@Testcontainers(disabledWithoutDocker = true)
class ReservationServiceIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("inventory");

  private static JdbcTemplate db;
  private static ReservationService service;
  private static SaleApplier applier;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(dataSource);
    service = new ReservationService(new ReservationRepository(db), new DataSourceTransactionManager(dataSource), new SimpleMeterRegistry());
    applier = new SaleApplier(db, new ReservationRepository(db), new DataSourceTransactionManager(dataSource));
  }

  @Test
  void reserveGrantsWhatIsAvailableAndUnknownSkusGetNothing() {
    stock("SKU-000001", 2);
    stock("SKU-000002", 5);
    String id = id();
    ReservationService.ReserveResult result = service.reserve(id, request(Map.of("SKU-000001", 3, "SKU-000002", 1, "SKU-999999", 1)));

    assertTrue(result.created());
    assertEquals(ReservationStateV1.RESERVED, result.reservation().state());
    assertEquals(Map.of("SKU-000001", 2, "SKU-000002", 1, "SKU-999999", 0), granted(result.reservation()));
    assertEquals(2, reservedStock("SKU-000001"));
    assertEquals(1, reservedStock("SKU-000002"));
    assertEquals(2, physicalStock("SKU-000001"), "reserving never changes physical stock");
  }

  @Test
  void allZeroReservationIsReleasedAndHoldsNothing() {
    stock("SKU-000003", 0);
    ReservationV1 r = service.reserve(id(), request(Map.of("SKU-000003", 2))).reservation();
    assertEquals(ReservationStateV1.RELEASED, r.state());
    assertEquals(0, reservedStock("SKU-000003"));
  }

  @Test
  void repeatedReserveReturnsTheStoredOutcomeWithoutASecondEffect() {
    stock("SKU-000004", 5);
    String id = id();
    ReserveRequestV1 request = request(Map.of("SKU-000004", 2));
    service.reserve(id, request);
    ReservationService.ReserveResult again = service.reserve(id, request);

    assertFalse(again.created());
    assertEquals(Map.of("SKU-000004", 2), granted(again.reservation()));
    assertEquals(2, reservedStock("SKU-000004"));
  }

  @Test
  void sameIdWithDifferentLinesIsAMismatch() {
    stock("SKU-000005", 5);
    String id = id();
    service.reserve(id, request(Map.of("SKU-000005", 1)));
    InternalApiException e = assertThrows(InternalApiException.class, () -> service.reserve(id, request(Map.of("SKU-000005", 2))));
    assertEquals(HttpStatus.CONFLICT, e.status());
    assertEquals("RESERVATION_MISMATCH", e.body().error());
  }

  @Test
  void commitDecrementsPhysicalAndReservedOnceAndRecordsOneSaleMovement() {
    stock("SKU-000006", 4);
    String id = id();
    service.reserve(id, request(Map.of("SKU-000006", 3)));

    assertEquals(ReservationStateV1.COMMITTED, service.commit(id).state());
    assertEquals(ReservationStateV1.COMMITTED, service.commit(id).state());   // idempotent repeat

    assertEquals(1, physicalStock("SKU-000006"));
    assertEquals(0, reservedStock("SKU-000006"));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE attempt_id = ? AND sku = ? AND movement_type = 'SALE'",
        Integer.class, id, "SKU-000006"));
    assertEquals(-3, db.queryForObject("SELECT quantity_delta FROM inventory_movements WHERE attempt_id = ?", Integer.class, id));
  }

  @Test
  void committedSalesStayReservedUntilAppliedThenGetOrderedMovements() {
    stock("SKU-000009", 10);
    String first = id();
    String second = id();
    service.reserve(first, request(Map.of("SKU-000009", 2)));
    service.reserve(second, request(Map.of("SKU-000009", 3)));
    service.commit(first);
    service.commit(second);

    // Before the applier runs, the sold units are still held: availability is already exact.
    assertEquals(10, db.queryForObject("SELECT physical_stock FROM inventory_positions WHERE sku = 'SKU-000009'", Integer.class));
    assertEquals(5, db.queryForObject("SELECT reserved_stock FROM inventory_positions WHERE sku = 'SKU-000009'", Integer.class));
    assertEquals(5, service.reserve(id(), request(Map.of("SKU-000009", 6))).reservation().lines().get(0).reservedQuantity(),
        "only 10 - 5 units are available");

    assertEquals(5, physicalStock("SKU-000009"));   // drains the queue: one position update for both sales
    assertEquals(List.of(8, 5), db.queryForList(
        "SELECT physical_after FROM inventory_movements WHERE sku = 'SKU-000009' AND movement_type = 'SALE' ORDER BY physical_after DESC",
        Integer.class), "each sale keeps its own physical_after, in commit order");
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sale_queue", Integer.class));
  }

  @Test
  void releasedReservationIsNotCommittable() {
    stock("SKU-000007", 0);
    String id = id();
    service.reserve(id, request(Map.of("SKU-000007", 1)));
    InternalApiException e = assertThrows(InternalApiException.class, () -> service.commit(id));
    assertEquals(HttpStatus.CONFLICT, e.status());
    assertEquals("RESERVATION_NOT_COMMITTABLE", e.body().error());
    assertEquals(ReservationStateV1.RELEASED, e.body().reservation().state());
  }

  @Test
  void concurrentReservationsNeverOverReserveTheLastUnit() throws Exception {
    stock("SKU-000008", 1);
    int threads = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<ReservationV1>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(pool.submit(() -> {
        go.await();
        return service.reserve(id(), request(Map.of("SKU-000008", 1))).reservation();
      }));
    }
    go.countDown();
    int granted = 0;
    for (Future<ReservationV1> f : results) granted += f.get().lines().get(0).reservedQuantity();
    pool.shutdown();

    assertEquals(1, granted);
    assertEquals(1, reservedStock("SKU-000008"));
    assertEquals(1, physicalStock("SKU-000008"));
  }

  private static ReserveRequestV1 request(Map<String, Integer> lines) {
    return new ReserveRequestV1("tx-" + UUID.randomUUID(),
        lines.entrySet().stream().map(e -> new ReserveLineV1(e.getKey(), e.getValue())).toList());
  }

  private static Map<String, Integer> granted(ReservationV1 r) {
    return r.lines().stream().collect(Collectors.toMap(ReservationLineV1::sku, ReservationLineV1::reservedQuantity));
  }

  private static String id() { return UUID.randomUUID().toString(); }

  private static void stock(String sku, int quantity) {
    db.update("UPDATE inventory_positions SET initial_stock = ?, physical_stock = ?, reserved_stock = 0 WHERE sku = ?", quantity, quantity, sku);
  }

  private static int physicalStock(String sku) {
    applier.drain();   // committed sales reach stock asynchronously
    return db.queryForObject("SELECT physical_stock FROM inventory_positions WHERE sku = ?", Integer.class, sku);
  }

  private static int reservedStock(String sku) {
    applier.drain();   // committed sales reach stock asynchronously
    return db.queryForObject("SELECT reserved_stock FROM inventory_positions WHERE sku = ?", Integer.class, sku);
  }
}
