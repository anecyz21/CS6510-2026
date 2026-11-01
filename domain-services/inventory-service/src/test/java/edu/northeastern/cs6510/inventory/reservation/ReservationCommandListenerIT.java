package edu.northeastern.cs6510.inventory.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryCommandV1;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import edu.northeastern.cs6510.inventory.messaging.DeadLetterPublisher;
import edu.northeastern.cs6510.inventory.messaging.KafkaConfig;
import edu.northeastern.cs6510.inventory.messaging.OutboxSender;
import edu.northeastern.cs6510.inventory.messaging.ReplyOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Reservation commands over Kafka against a real broker and Inventory's own migrations (005 T020, research R4). */
@Testcontainers(disabledWithoutDocker = true)
class ReservationCommandListenerIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("inventory");
  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate db;
  private static KafkaProducer<String, String> producer;
  private static KafkaConsumer<String, String> replies;
  private static AnnotationConfigApplicationContext context;
  private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(dataSource);
    db.update("UPDATE inventory_positions SET physical_stock = 10, reserved_stock = 0 WHERE sku = 'SKU-000001'");
    try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(InventoryCommandV1.TOPIC, 12, (short) 1), new NewTopic(InventoryReplyV1.TOPIC, 12, (short) 1)))
          .all().get();
    }
    producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    replies = new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, "test-replies", ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    replies.subscribe(List.of(InventoryReplyV1.TOPIC));
    context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers())));
    context.register(TestConfig.class);
    context.refresh();
  }

  @AfterAll
  static void tearDown() {
    if (context != null) context.close();
    if (producer != null) producer.close();
    if (replies != null) replies.close();
  }

  @Test
  void aCommandIsAnsweredOnceWithItsCorrelationAndCausation() throws Exception {
    String attemptId = UUID.randomUUID().toString();
    String commandId = send(InventoryCommandV1.reserve(UUID.randomUUID().toString(), attemptId, "tx-1",
        List.of(new ReserveLineV1("SKU-000001", 2))));

    ConsumerRecord<String, String> record = nextReplyFor(attemptId);
    InventoryReplyV1 reply = JSON.readValue(record.value(), InventoryReplyV1.class);
    Envelope envelope = envelope(record);
    assertEquals(InventoryReplyV1.RESERVED, reply.messageType());
    assertEquals(ReservationStateV1.RESERVED, reply.reservation().state());
    assertEquals(2, reply.reservation().lines().get(0).reservedQuantity());
    assertEquals(attemptId, envelope.correlationId());
    assertEquals(commandId, envelope.causationId());
    assertEquals("inventory", envelope.producer());
    assertEquals(2, reservedStock());
    awaitPublished(attemptId, 1);
  }

  @Test
  void aRepeatedReserveRepliesAgainWithoutASecondStockEffect() throws Exception {
    String attemptId = UUID.randomUUID().toString();
    int before = reservedStock();
    InventoryCommandV1 reserve = InventoryCommandV1.reserve(UUID.randomUUID().toString(), attemptId, "tx-2",
        List.of(new ReserveLineV1("SKU-000001", 1)));
    send(reserve);
    nextReplyFor(attemptId);
    send(InventoryCommandV1.reserve(UUID.randomUUID().toString(), attemptId, "tx-2", reserve.lines()));

    InventoryReplyV1 again = JSON.readValue(nextReplyFor(attemptId).value(), InventoryReplyV1.class);
    assertEquals(InventoryReplyV1.RESERVED, again.messageType());
    assertEquals(before + 1, reservedStock(), "the repeat reserved nothing more");
    awaitPublished(attemptId, 2);
  }

  @Test
  void commitForAnUnknownAttemptRepliesConflict() throws Exception {
    String attemptId = UUID.randomUUID().toString();
    send(InventoryCommandV1.commit(UUID.randomUUID().toString(), attemptId));

    InventoryReplyV1 reply = JSON.readValue(nextReplyFor(attemptId).value(), InventoryReplyV1.class);
    assertEquals(InventoryReplyV1.CONFLICT, reply.messageType());
    assertEquals("RESERVATION_NOT_FOUND", reply.error().code());
    assertNull(reply.reservation());
  }

  @Test
  void anUnparseableCommandIsDeadLetteredWithoutAReply() throws Exception {
    producer.send(record(UUID.randomUUID().toString(), "attempt-poison", "{not json")).get();
    String attemptId = UUID.randomUUID().toString();
    send(InventoryCommandV1.cancel(UUID.randomUUID().toString(), attemptId));

    InventoryReplyV1 reply = JSON.readValue(nextReplyFor(attemptId).value(), InventoryReplyV1.class);
    assertEquals(InventoryReplyV1.CANCELLED, reply.messageType(), "the next command still flows");
    assertEquals(1.0, context.getBean(MeterRegistry.class).find("messaging.dlt.published").counter().count());
  }

  private static String send(InventoryCommandV1 command) throws Exception {
    producer.send(record(command.messageId(), command.attemptId(), JSON.writeValueAsString(command), command.messageType())).get();
    return command.messageId();
  }

  private static ProducerRecord<String, String> record(String messageId, String key, String value) {
    return record(messageId, key, value, "ReserveRequested");
  }

  private static ProducerRecord<String, String> record(String messageId, String key, String value, String type) {
    ProducerRecord<String, String> record = new ProducerRecord<>(InventoryCommandV1.TOPIC, key, value);
    new Envelope(messageId, type, 1, "checkout", Instant.now(), key, null).toHeaders().forEach((k, v) -> record.headers().add(k, v));
    return record;
  }

  private static final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

  /** The next reply for {@code attemptId} not returned before. */
  private static synchronized ConsumerRecord<String, String> nextReplyFor(String attemptId) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(deadline)) {
      for (ConsumerRecord<String, String> r : replies.poll(Duration.ofMillis(200))) seen.add(r);
      for (int i = 0; i < seen.size(); i++) {
        if (attemptId.equals(seen.get(i).key())) return seen.remove(i);
      }
    }
    throw new AssertionError("no reply for " + attemptId + " within 30 s");
  }

  private static Envelope envelope(ConsumerRecord<String, String> record) {
    Map<String, byte[]> headers = new HashMap<>();
    record.headers().forEach(h -> headers.put(h.key(), h.value()));
    return Envelope.fromHeaders(headers);
  }

  private static int reservedStock() {
    return db.queryForObject("SELECT reserved_stock FROM inventory_positions WHERE sku = 'SKU-000001'", Integer.class);
  }

  private static void awaitPublished(String attemptId, int rows) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (Instant.now().isBefore(deadline)) {
      Integer published = db.queryForObject("SELECT COUNT(*) FROM reply_outbox WHERE attempt_id = ? AND published_at IS NOT NULL",
          Integer.class, attemptId);
      if (published != null && published == rows) return;
      Thread.sleep(100);
    }
    throw new AssertionError("reply_outbox rows for " + attemptId + " were not all marked published");
  }

  @Configuration
  @EnableKafka
  @Import({KafkaConfig.class, DeadLetterPublisher.class, OutboxSender.class, ReplyOutbox.class, ReservationRepository.class,
      ReservationService.class, ReservationCommandListener.class})
  static class TestConfig {
    @Bean DataSource dataSource() { return dataSource; }
    @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean ObjectMapper objectMapper() { return JSON; }
    @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    /** Boot's conversions, so @Value durations such as "24h" resolve outside a Boot application. */
    @Bean static ConversionService conversionService() { return ApplicationConversionService.getSharedInstance(); }
  }
}
