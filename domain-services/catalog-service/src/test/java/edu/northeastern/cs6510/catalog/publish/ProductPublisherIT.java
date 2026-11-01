package edu.northeastern.cs6510.catalog.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.catalog.CatalogRepository;
import edu.northeastern.cs6510.catalog.messaging.KafkaConfig;
import edu.northeastern.cs6510.contracts.Fixtures;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Catalog's version-based outbox against a real broker and Catalog's own migrations (005 T018, research R2). */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class ProductPublisherIT {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("catalog");
  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

  private static JdbcTemplate db;
  private static CatalogRepository repository;
  private static ProductPublisher publisher;
  private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

  @BeforeAll
  static void setUp() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(dataSource);
    repository = new CatalogRepository(db);
    KafkaConfig config = new KafkaConfig();
    try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(config.productsTopic())).all().get();
    }
    KafkaTemplate<String, String> template = config.productTemplate(config.productProducerFactory(KAFKA.getBootstrapServers()));
    publisher = new ProductPublisher(repository, template, JSON, KAFKA.getBootstrapServers());
  }

  @Test
  @Order(1)
  void firstPassPublishesEveryItemKeyedBySku() throws Exception {
    int items = db.queryForObject("SELECT COUNT(*) FROM catalog_items", Integer.class);
    assertTrue(items > 0, "the seed migration provides items");

    assertEquals(items, publisher.publishIfPending());

    List<ConsumerRecord<String, String>> records = readAll();
    assertEquals(items, records.size());
    long version = repository.catalogVersion();
    assertEquals(version, repository.publishedVersion());
    JsonNode fixture = Fixtures.read("messages/product-changed-v1.json");
    for (ConsumerRecord<String, String> record : records) {
      JsonNode value = JSON.readTree(record.value());
      Fixtures.assertCovers(fixture, value, record.key());
      assertEquals(record.key(), value.get("sku").asText());
      assertEquals(version, value.get("catalogVersion").asLong());
      Map<String, byte[]> headers = new HashMap<>();
      record.headers().forEach(h -> headers.put(h.key(), h.value()));
      Envelope envelope = Envelope.fromHeaders(headers);
      assertEquals("catalog", envelope.producer());
      assertEquals(ProductEventV1.MESSAGE_TYPE, envelope.messageType());
      assertEquals(value.get("messageId").asText(), envelope.messageId());
      JSON.readValue(record.value(), ProductEventV1.class).validate();
    }
  }

  @Test
  @Order(2)
  void aSecondPassPublishesNothingNew() {
    assertEquals(0, publisher.publishIfPending());
  }

  @Test
  @Order(3)
  void aNewVersionRepublishesEverySku() throws Exception {
    int before = readAll().size();
    int items = db.queryForObject("SELECT COUNT(*) FROM catalog_items", Integer.class);
    db.update("UPDATE catalog_metadata SET catalog_version = catalog_version + 1 WHERE id = 1");

    assertEquals(items, publisher.publishIfPending());
    assertEquals(before + items, readAll().size());
    assertEquals(repository.catalogVersion(), repository.publishedVersion());
  }

  @Test
  @Order(4)
  void anOperatorResetRepublishesTheSameVersion() {
    long version = repository.catalogVersion();
    repository.resetPublication();
    assertTrue(publisher.publishIfPending() > 0);
    assertEquals(version, repository.publishedVersion());
  }

  private static List<ConsumerRecord<String, String>> readAll() {
    Map<String, Object> props = Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    TopicPartition partition = new TopicPartition(ProductEventV1.TOPIC, 0);
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.assign(List.of(partition));
      consumer.seekToBeginning(List.of(partition));
      long end = consumer.endOffsets(List.of(partition)).get(partition);
      while (consumer.position(partition) < end) consumer.poll(Duration.ofMillis(500)).forEach(out::add);
    }
    return out;
  }
}
