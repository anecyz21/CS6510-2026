package edu.northeastern.cs6510.analytics.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import edu.northeastern.cs6510.analytics.ops.ConsumerLag;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Ingest from a real broker into a real MySQL with Analytics' own migrations (T091, FR-022, FR-024). */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ScanIngestIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("analytics");
  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.kafka.bootstrap-servers", () -> {
      createTopic(KAFKA.getBootstrapServers());
      return KAFKA.getBootstrapServers();
    });
  }

  @Autowired JdbcTemplate db;
  @Autowired ConsumerLag lag;
  @MockitoSpyBean ScanPipeline pipeline;

  @Test
  void duplicatesOfOneEventIdGiveOneRowWhoseSequenceNeverChanges() {
    String once = UUID.randomUUID().toString();
    String other = UUID.randomUUID().toString();
    send(event(once, "SKU-000001"), event(once, "SKU-000001"), event(other, "SKU-000002"), event(once, "SKU-000001"));
    await("both events ingested", () -> count("SELECT COUNT(*) FROM ingested_scans WHERE event_id IN ('" + once + "','" + other + "')") == 2);
    long sequence = sequenceOf(once);

    send(event(once, "SKU-000001"));
    awaitDrained();
    assertEquals(1, count("SELECT COUNT(*) FROM ingested_scans WHERE event_id = '" + once + "'"));
    assertEquals(sequence, sequenceOf(once), "a redelivered duplicate never moves the scan");
    assertEquals(count("SELECT MAX(sequence_number) FROM ingested_scans"), count("SELECT last_sequence FROM ingest_state"));
  }

  @Test
  void unreadableAndNewerVersionValuesAreRejectedAndAcknowledged() {
    long before = count("SELECT COUNT(*) FROM ingest_rejections");
    String v2 = event(UUID.randomUUID().toString(), "SKU-000003").replace("\"schemaVersion\":1", "\"schemaVersion\":2");
    send("not-json", v2);
    await("both values rejected", () -> count("SELECT COUNT(*) FROM ingest_rejections") == before + 2);
    awaitDrained();
    List<String> reasons = db.queryForList("SELECT reason FROM ingest_rejections ORDER BY kafka_offset DESC LIMIT 2", String.class);
    assertTrue(reasons.stream().anyMatch(r -> r.startsWith("unparseable")), reasons.toString());
    assertTrue(reasons.stream().anyMatch(r -> r.contains("schemaVersion 2")), reasons.toString());
  }

  @Test
  void aFailedBatchCommitsNoOffsetAndRedeliveryAssignsTheSameSequences() {
    List<String> ids = IntStream.range(0, 3).mapToObj(i -> UUID.randomUUID().toString()).toList();
    Map<String, Long> firstAttempt = new HashMap<>();
    AtomicBoolean failed = new AtomicBoolean();
    doAnswer(call -> {
      call.callRealMethod();
      List<AcceptedScanV1> events = call.getArgument(0);
      if (!failed.get() && events.stream().anyMatch(e -> ids.contains(e.eventId()))) {
        db.query("SELECT event_id, sequence_number FROM ingested_scans WHERE event_id IN (" + quoted(ids) + ")",
            rs -> { firstAttempt.put(rs.getString(1), rs.getLong(2)); });
        failed.set(true);
        throw new IllegalStateException("injected failure after the pipeline ran");
      }
      return null;
    }).when(pipeline).process(anyList());

    send(ids.stream().map(id -> event(id, "SKU-000004")).toArray(String[]::new));
    await("the batch is redelivered and ingested", () ->
        count("SELECT COUNT(*) FROM ingested_scans WHERE event_id IN (" + quoted(ids) + ")") == ids.size());
    assertTrue(failed.get(), "the injected failure ran");
    assertFalse(firstAttempt.isEmpty());
    firstAttempt.forEach((id, seq) -> assertEquals(seq, sequenceOf(id), "same sequence on redelivery for " + id));
    awaitDrained();
  }

  private void awaitDrained() {
    await("consumer lag 0", () -> Long.valueOf(0).equals(lag.lag()));
  }

  private long sequenceOf(String eventId) {
    return db.queryForObject("SELECT sequence_number FROM ingested_scans WHERE event_id = ?", Long.class, eventId);
  }

  private long count(String sql) {
    Long n = db.queryForObject(sql, Long.class);
    return n == null ? 0 : n;
  }

  private static String quoted(List<String> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }

  private static String event(String eventId, String sku) {
    return "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"eventType\":\"AcceptedScan\",\"transactionId\":\"tx-1\","
        + "\"stationId\":\"station-1\",\"sku\":\"" + sku + "\",\"acceptedAt\":\"" + Instant.now() + "\",\"outboxId\":1}";
  }

  /** Sends the values in order as one producer batch. */
  private static void send(String... values) {
    Map<String, Object> props = Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ProducerConfig.LINGER_MS_CONFIG, 50,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      for (String value : values) producer.send(new ProducerRecord<>(AcceptedScanV1.TOPIC, "tx-1", value));
      producer.flush();
    }
  }

  private static void await(String what, BooleanSupplier condition) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) return;
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted waiting for " + what);
      }
    }
    fail("timed out waiting for " + what);
  }

  /**
   * Creates the scan and product topics and publishes names for SKU-000001..SKU-000010 at catalog
   * version 1, as Catalog would (005: names come from catalog.products.v1, not an HTTP call).
   */
  private static void createTopic(String bootstrap) {
    try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
      if (admin.listTopics().names().get().contains(AcceptedScanV1.TOPIC)) return;
      admin.createTopics(List.of(new NewTopic(AcceptedScanV1.TOPIC, 1, (short) 1), new NewTopic(ProductEventV1.TOPIC, 1, (short) 1))).all().get();
    } catch (Exception e) {
      throw new IllegalStateException("cannot create topics", e);
    }
    Map<String, Object> props = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      for (int i = 1; i <= 10; i++) {
        String sku = String.format("SKU-%06d", i);
        String messageId = UUID.randomUUID().toString();
        String value = String.format("{\"schemaVersion\":1,\"messageId\":\"%s\",\"messageType\":\"ProductChanged\",\"catalogVersion\":1,"
            + "\"sku\":\"%s\",\"name\":\"Catalog item %d\",\"priceCents\":%d,\"active\":true}", messageId, sku, i, 100 + i);
        ProducerRecord<String, String> record = new ProducerRecord<>(ProductEventV1.TOPIC, sku, value);
        new Envelope(messageId, ProductEventV1.MESSAGE_TYPE, 1, "catalog", Instant.now(), null, null).toHeaders()
            .forEach((k, v) -> record.headers().add(k, v));
        producer.send(record).get();
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot publish the catalog", e);
    }
  }

}
