package edu.northeastern.cs6510.checkout.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.checkout.messaging.DeadLetterPublisher;
import edu.northeastern.cs6510.checkout.messaging.KafkaConfig;
import edu.northeastern.cs6510.checkout.scan.ProductLookup;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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

/** Checkout's catalog copy built from catalog.products.v1 against a real broker and Checkout's own migrations (005 T019, research R3). */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class ProductEventListenerIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("checkout");
  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate db;
  private static KafkaProducer<String, String> producer;
  private static AnnotationConfigApplicationContext context;
  private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(dataSource);
    try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(ProductEventV1.TOPIC, 1, (short) 1))).all().get();
    }
    producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    // Published before the listener starts: the catch-up target at assignment is 3.
    publish("SKU-A", "Apple", 250, 1, true);
    publish("SKU-B", "Bread", 100, 1, true);
    publish("SKU-C", "Cheese", 500, 1, true);
    context = start();
  }

  @AfterAll
  static void tearDown() {
    if (context != null) context.close();
    if (producer != null) producer.close();
  }

  @Test
  @Order(1)
  void replaysTheTopicAndStoresItsPositionWithTheRows() {
    CatalogProjection projection = context.getBean(CatalogProjection.class);
    await(projection::caughtUp);
    assertEquals(3, projection.size());
    assertEquals(2L, projection.topicOffset());
    assertEquals(2L, db.queryForObject("SELECT topic_offset FROM projection_state WHERE id = 1", Long.class));
    assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM catalog_projection", Integer.class));
    assertInstanceOf(ProductLookup.UnknownSku.class, new ProductLookup(projection).lookup("SKU-NOPE"), "caught up: a miss is unknown");
  }

  @Test
  @Order(2)
  void anInactiveProductIsDeletedAndAnOlderVersionIsSkipped() {
    CatalogProjection projection = context.getBean(CatalogProjection.class);
    publish("SKU-C", "Cheese", 500, 1, false);
    publish("SKU-A", "Apple (new)", 260, 2, true);
    publish("SKU-A", "Apple (old)", 250, 1, true);
    await(() -> Long.valueOf(5).equals(projection.topicOffset()));

    assertTrue(projection.find("SKU-C").isEmpty());
    assertEquals("Apple (new)", projection.find("SKU-A").orElseThrow().name());
    assertEquals(260, projection.find("SKU-A").orElseThrow().priceCents());
    assertEquals(2L, projection.catalogVersion());
  }

  @Test
  @Order(3)
  void anInvalidRecordIsDeadLetteredAndTheProjectionMovesPastIt() {
    CatalogProjection projection = context.getBean(CatalogProjection.class);
    producer.send(new ProducerRecord<>(ProductEventV1.TOPIC, "SKU-X", "{not json"));
    publish("SKU-D", "Dates", 700, 2, true);
    await(() -> projection.find("SKU-D").isPresent());
    assertEquals(7L, projection.topicOffset());
    assertEquals(1.0, context.getBean(MeterRegistry.class).find("messaging.dlt.published").counter().count());
  }

  @Test
  @Order(4)
  void aRestartResumesFromTheStoredOffsetAndARebuildReplaysEverything() {
    context.close();
    publish("SKU-E", "Eggs", 300, 2, true);
    context = start();
    CatalogProjection projection = context.getBean(CatalogProjection.class);
    await(projection::caughtUp);
    assertEquals(8L, projection.topicOffset());
    assertTrue(projection.find("SKU-E").isPresent());

    context.getBean(ProductEventListener.class).rebuild();
    assertFalse(projection.find("SKU-A").isPresent(), "the copy is dropped first");
    assertInstanceOf(ProductLookup.Unverifiable.class, new ProductLookup(projection).lookup("SKU-A"), "replaying: a miss is unverifiable");
    await(projection::caughtUp);
    assertEquals("Apple (new)", projection.find("SKU-A").orElseThrow().name());
    assertEquals(4, projection.size(), "A, B, D and E; the deleted C stays deleted");
  }

  private static AnnotationConfigApplicationContext start() {
    AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
    ctx.getEnvironment().getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers())));
    ctx.register(TestConfig.class);
    ctx.refresh();
    return ctx;
  }

  private static void publish(String sku, String name, long price, long version, boolean active) {
    String messageId = UUID.randomUUID().toString();
    try {
      String value = JSON.writeValueAsString(new ProductEventV1(1, messageId, ProductEventV1.MESSAGE_TYPE, version, sku, name, price, active));
      ProducerRecord<String, String> record = new ProducerRecord<>(ProductEventV1.TOPIC, sku, value);
      new Envelope(messageId, ProductEventV1.MESSAGE_TYPE, 1, "catalog", Instant.now(), null, null).toHeaders()
          .forEach((k, v) -> record.headers().add(k, v));
      producer.send(record).get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void await(BooleanSupplier condition) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(deadline)) throw new AssertionError("condition not met within 30 s");
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  @Configuration
  @EnableKafka
  @Import({KafkaConfig.class, DeadLetterPublisher.class, CatalogProjection.class, ProductEventListener.class})
  static class TestConfig {
    @Bean DataSource dataSource() { return dataSource; }
    @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean ObjectMapper objectMapper() { return JSON; }
    @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
  }
}
