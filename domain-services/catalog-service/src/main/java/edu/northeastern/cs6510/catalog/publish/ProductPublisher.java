package edu.northeastern.cs6510.catalog.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.catalog.CatalogRepository;
import edu.northeastern.cs6510.catalog.CatalogRepository.PublishedItem;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import edu.northeastern.cs6510.contracts.messaging.MessageIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the catalog to {@code catalog.products.v1} (005 research R2). Item rows change only
 * through migrations, which bump {@code catalog_version}; {@code catalog_publication} records the
 * last version every item was acknowledged for, so the version comparison is the outbox query.
 *
 * <p>A pass publishes every item, active or not, keyed by SKU, waits for every acknowledgment, and
 * only then records the version. A crash or broker error before that republishes the same version
 * on a later pass, which consumers ignore. If the version is recorded but the topic is empty (the
 * broker's volume was reset), the catalog is republished so consumers can still rebuild.
 */
@Component
public class ProductPublisher implements DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(ProductPublisher.class);
  public static final String PRODUCER = "catalog";

  private final CatalogRepository repository;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper json;
  private final AdminClient admin;

  public ProductPublisher(CatalogRepository repository, KafkaTemplate<String, String> productTemplate, ObjectMapper json,
                          @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    this.repository = repository;
    this.kafka = productTemplate;
    this.json = json;
    this.admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 2000, "default.api.timeout.ms", 3000));
  }

  @Scheduled(initialDelay = 0, fixedDelayString = "${catalog.publish.interval:5s}")
  public void tick() {
    try {
      publishIfPending();
    } catch (RuntimeException e) {
      log.warn("catalog publication pass failed, retrying next tick: {}", e.toString());
    }
  }

  /** One pass. Returns the number of records published (0 when nothing was pending). */
  public int publishIfPending() {
    long version = repository.catalogVersion();
    Long published = repository.publishedVersion();
    boolean pending = published == null || published < version;
    if (!pending && topicEndOffset() > 0) return 0;
    if (!pending) log.info("catalog version {} is recorded as published but {} is empty; republishing", version, ProductEventV1.TOPIC);

    List<PublishedItem> items = repository.allItems();
    Instant now = Instant.now();
    List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(items.size());
    for (PublishedItem item : items) sends.add(kafka.send(record(item, version, now)));
    kafka.flush();
    try {
      CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing catalog version " + version, e);
    } catch (Exception e) {
      throw new IllegalStateException("catalog version " + version + " was not fully acknowledged", e);
    }
    repository.markPublished(version, Instant.now());
    log.info("published catalog version {}: {} products", version, items.size());
    return items.size();
  }

  ProducerRecord<String, String> record(PublishedItem item, long version, Instant occurredAt) {
    String messageId = MessageIds.next();
    ProductEventV1 event = new ProductEventV1(ProductEventV1.SCHEMA_VERSION, messageId, ProductEventV1.MESSAGE_TYPE, version,
        item.item().sku(), item.item().name(), item.item().priceCents(), item.active());
    String value;
    try {
      value = json.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize product " + item.item().sku(), e);
    }
    ProducerRecord<String, String> record = new ProducerRecord<>(ProductEventV1.TOPIC, item.item().sku(), value);
    new Envelope(messageId, ProductEventV1.MESSAGE_TYPE, ProductEventV1.SCHEMA_VERSION, PRODUCER, occurredAt, null, null)
        .toHeaders().forEach((name, bytes) -> record.headers().add(name, bytes));
    return record;
  }

  /** End offset of the single partition; a broker error propagates so the pass is retried. */
  private long topicEndOffset() {
    TopicPartition partition = new TopicPartition(ProductEventV1.TOPIC, 0);
    try {
      return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).partitionResult(partition).get(3, TimeUnit.SECONDS).offset();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted reading the end offset of " + ProductEventV1.TOPIC, e);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read the end offset of " + ProductEventV1.TOPIC, e);
    }
  }

  @Override
  public void destroy() { admin.close(); }
}
