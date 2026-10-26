package edu.northeastern.cs6510.checkout.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** The accepted-scan relay against a real broker and Checkout's own migrations (T092, research R4). */
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("checkout");
  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate db;
  private static AnnotationConfigApplicationContext live;
  private static AcceptedScanEventMapper mapper;

  @BeforeAll
  static void setUp() {
    dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(dataSource);
    mapper = new AcceptedScanEventMapper(Jackson2ObjectMapperBuilder.json().build());
    live = kafkaContext(KAFKA.getBootstrapServers());
  }

  @AfterAll
  static void tearDown() {
    if (live != null) live.close();
  }

  @BeforeEach
  void emptyOutbox() {
    db.update("DELETE FROM accepted_scan_outbox");
  }

  @Test
  void rowsAreMarkedOnlyAfterTheBrokerAcknowledgedThem() {
    List<String> ids = List.of(accept("SKU-000001"), accept("SKU-000002"), accept("SKU-000003"));
    OutboxRelay relay = relay(live);
    AtomicLong pendingAtAck = new AtomicLong(-1);
    relay.afterAcknowledged = () -> pendingAtAck.set(pendingCount());

    assertEquals(3, relay.publishOnce());
    assertEquals(3, pendingAtAck.get(), "nothing is marked before every send has been acknowledged");
    assertEquals(0, pendingCount());
    assertTrue(consumedEventIds().containsAll(ids));
  }

  @Test
  void withKafkaDownRowsStayPendingAndScansKeepCommitting() {
    try (AnnotationConfigApplicationContext dead = kafkaContext("127.0.0.1:1")) {
      OutboxRelay relay = relay(dead);
      accept("SKU-000004");
      assertThrows(RuntimeException.class, relay::publishOnce);
      accept("SKU-000005");
      assertEquals(2, pendingCount(), "scans committed while the broker was away stay pending");
    }
    assertEquals(2, relay(live).publishOnce(), "the backlog drains once a broker is reachable");
  }

  @Test
  void aRowCommittedLateWithALowerIdIsStillPublished() throws Exception {
    OutboxRelay relay = relay(live);
    String late;
    try (Connection open = dataSource.getConnection()) {
      open.setAutoCommit(false);
      late = UUID.randomUUID().toString();
      try (var insert = open.prepareStatement(
          "INSERT INTO accepted_scan_outbox (event_id, transaction_id, station_id, sku, accepted_at) VALUES (?, 'tx-late', 'station-1', 'SKU-000006', CURRENT_TIMESTAMP(6))")) {
        insert.setString(1, late);
        insert.executeUpdate();
      }
      String early = accept("SKU-000007");   // higher outbox_id, committed first
      assertEquals(1, relay.publishOnce());
      assertTrue(consumedEventIds().contains(early));
      open.commit();
    }
    assertEquals(1, relay.publishOnce(), "no watermark: the lower id is picked up on a later pass");
    assertTrue(consumedEventIds().contains(late));
  }

  @Test
  void aCrashBetweenAcknowledgmentAndMarkingRepublishesTheSameEventId() {
    String id = accept("SKU-000008");
    OutboxRelay relay = relay(live);
    AtomicBoolean crash = new AtomicBoolean(true);
    relay.afterAcknowledged = () -> {
      if (crash.getAndSet(false)) throw new IllegalStateException("simulated crash after acknowledgment");
    };
    assertThrows(IllegalStateException.class, relay::publishOnce);
    assertEquals(1, pendingCount());
    assertEquals(1, relay.publishOnce());
    assertEquals(2, consumedEventIds().stream().filter(id::equals).count(), "republished with the same eventId");
  }

  @Test
  void onlyOneRelayHoldsTheLock() {
    OutboxRelay first = relay(live);
    OutboxRelay second = relay(live);
    try {
      assertTrue(first.holdsLock());
      assertFalse(second.holdsLock());
      first.releaseLock();
      assertTrue(second.holdsLock(), "the lock passes on once released");
    } finally {
      first.releaseLock();
      second.releaseLock();
    }
  }

  private static AnnotationConfigApplicationContext kafkaContext(String bootstrap) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("spring.kafka.bootstrap-servers", bootstrap)));
    context.register(KafkaProducerConfig.class);
    context.refresh();
    return context;
  }

  @SuppressWarnings("unchecked")
  private static OutboxRelay relay(AnnotationConfigApplicationContext context) {
    return new OutboxRelay(dataSource, context.getBean("acceptedScanTemplate", KafkaTemplate.class), context.getBean(KafkaAdmin.class),
        mapper, null, Duration.ofMillis(200), Duration.ofHours(24));
  }

  private static String accept(String sku) {
    String id = UUID.randomUUID().toString();
    db.update("INSERT INTO accepted_scan_outbox (event_id, transaction_id, station_id, sku, accepted_at) VALUES (?, 'tx-1', 'station-1', ?, CURRENT_TIMESTAMP(6))",
        id, sku);
    return id;
  }

  private static long pendingCount() {
    return db.queryForObject("SELECT COUNT(*) FROM accepted_scan_outbox WHERE published_at IS NULL", Long.class);
  }

  /** Every eventId on the topic, in order, read from the beginning. */
  private static List<String> consumedEventIds() {
    Map<String, Object> props = Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    List<String> ids = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      TopicPartition partition = new TopicPartition(AcceptedScanV1.TOPIC, 0);
      consumer.assign(Set.of(partition));
      consumer.seekToBeginning(Set.of(partition));
      long end = consumer.endOffsets(Set.of(partition)).get(partition);
      while (consumer.position(partition) < end) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          ids.add(new String(record.headers().lastHeader("eventId").value()));
        }
      }
    }
    return ids;
  }
}
