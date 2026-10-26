package edu.northeastern.cs6510.analytics.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import com.sun.net.httpserver.HttpServer;
import edu.northeastern.cs6510.analytics.ops.ConsumerLag;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.AfterAll;
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
  private static final HttpServer CATALOG = stubCatalog();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.kafka.bootstrap-servers", () -> {
      createTopic(KAFKA.getBootstrapServers());
      return KAFKA.getBootstrapServers();
    });
    registry.add("catalog.url", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
  }

  @Autowired JdbcTemplate db;
  @Autowired ConsumerLag lag;
  @MockitoSpyBean ScanPipeline pipeline;

  @AfterAll
  static void stopCatalog() { CATALOG.stop(0); }

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

  private static void createTopic(String bootstrap) {
    try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
      if (!admin.listTopics().names().get().contains(AcceptedScanV1.TOPIC)) {
        admin.createTopics(List.of(new NewTopic(AcceptedScanV1.TOPIC, 1, (short) 1))).all().get();
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot create topic", e);
    }
  }

  /** A Catalog that names SKU-000001..SKU-000010 at version 1. */
  private static HttpServer stubCatalog() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/internal/v1/catalog-version", exchange -> reply(exchange,
          "{\"catalogVersion\":1,\"updatedAt\":\"2026-10-24T12:00:00Z\",\"activeItemCount\":10}"));
      String items = IntStream.rangeClosed(1, 10)
          .mapToObj(i -> String.format("{\"sku\":\"SKU-%06d\",\"name\":\"Catalog item %d\",\"priceCents\":%d}", i, i, 100 + i))
          .collect(Collectors.joining(","));
      server.createContext("/internal/v1/products", exchange -> reply(exchange,
          "{\"catalogVersion\":1,\"items\":[" + items + "],\"nextAfterSku\":null}"));
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
