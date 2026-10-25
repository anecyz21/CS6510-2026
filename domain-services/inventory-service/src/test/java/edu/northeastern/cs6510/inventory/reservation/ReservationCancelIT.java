package edu.northeastern.cs6510.inventory.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import edu.northeastern.cs6510.inventory.api.InternalApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cancel, tombstones and the commit-versus-cancel race against a real MySQL 8.4 (T063, FR-017). */
@Testcontainers(disabledWithoutDocker = true)
class ReservationCancelIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("inventory");

  private static JdbcTemplate db;
  private static ReservationService service;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(dataSource);
    service = new ReservationService(new ReservationRepository(db), new DataSourceTransactionManager(dataSource), new SimpleMeterRegistry());
  }

  @Test
  void cancelReleasesReservedStockWithoutTouchingPhysicalStock() {
    stock("SKU-000001", 5);
    String id = id();
    service.reserve(id, request("SKU-000001", 3));
    assertEquals(3, reserved("SKU-000001"));

    assertEquals(ReservationStateV1.RELEASED, service.cancel(id).state());
    assertEquals(0, reserved("SKU-000001"));
    assertEquals(5, physical("SKU-000001"));
    assertEquals(ReservationStateV1.RELEASED, service.cancel(id).state(), "repeat cancel is idempotent");
    assertEquals(0, reserved("SKU-000001"));
  }

  @Test
  void cancelOfAnUnknownIdLeavesATombstoneThatALateReserveCannotUse() {
    stock("SKU-000002", 5);
    String id = id();
    ReservationV1 tombstone = service.cancel(id);
    assertEquals(ReservationStateV1.CANCELLED, tombstone.state());
    assertTrue(tombstone.lines().isEmpty());

    ReservationService.ReserveResult late = service.reserve(id, request("SKU-000002", 2));
    assertEquals(ReservationStateV1.CANCELLED, late.reservation().state());
    assertEquals(0, reserved("SKU-000002"));
  }

  @Test
  void committedReservationCannotBeCancelled() {
    stock("SKU-000003", 5);
    String id = id();
    service.reserve(id, request("SKU-000003", 1));
    service.commit(id);

    InternalApiException e = assertThrows(InternalApiException.class, () -> service.cancel(id));
    assertEquals(HttpStatus.CONFLICT, e.status());
    assertEquals("RESERVATION_COMMITTED", e.body().error());
    assertEquals(ReservationStateV1.COMMITTED, e.body().reservation().state());
    assertEquals(4, physical("SKU-000003"));
  }

  @Test
  void commitAndCancelRacingOnOneReservationHaveExactlyOneWinner() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 25; round++) {
        stock("SKU-000004", 10);
        String id = id();
        service.reserve(id, request("SKU-000004", 2));
        CountDownLatch go = new CountDownLatch(1);
        Future<Object> commit = pool.submit(attempt(go, () -> service.commit(id)));
        Future<Object> cancel = pool.submit(attempt(go, () -> service.cancel(id)));
        go.countDown();
        Object committed = commit.get();
        Object cancelled = cancel.get();

        ReservationStateV1 finalState = service.get(id).state();
        if (finalState == ReservationStateV1.COMMITTED) {
          assertTrue(committed instanceof ReservationV1, "commit won");
          assertEquals("RESERVATION_COMMITTED", ((InternalApiException) cancelled).body().error());
          assertEquals(8, physical("SKU-000004"));
        } else {
          assertEquals(ReservationStateV1.RELEASED, finalState);
          assertEquals("RESERVATION_NOT_COMMITTABLE", ((InternalApiException) committed).body().error());
          assertEquals(10, physical("SKU-000004"));
        }
        assertEquals(0, reserved("SKU-000004"));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static Callable<Object> attempt(CountDownLatch go, Callable<ReservationV1> command) {
    return () -> {
      go.await();
      try {
        return command.call();
      } catch (InternalApiException loser) {
        return loser;
      }
    };
  }

  private static ReserveRequestV1 request(String sku, int quantity) {
    return new ReserveRequestV1("tx-" + UUID.randomUUID(), List.of(new ReserveLineV1(sku, quantity)));
  }

  private static String id() { return UUID.randomUUID().toString(); }

  private static void stock(String sku, int quantity) {
    db.update("UPDATE inventory_positions SET initial_stock = ?, physical_stock = ?, reserved_stock = 0 WHERE sku = ?", quantity, quantity, sku);
  }

  private static int physical(String sku) {
    return db.queryForObject("SELECT physical_stock FROM inventory_positions WHERE sku = ?", Integer.class, sku);
  }

  private static int reserved(String sku) {
    return db.queryForObject("SELECT reserved_stock FROM inventory_positions WHERE sku = ?", Integer.class, sku);
  }
}
