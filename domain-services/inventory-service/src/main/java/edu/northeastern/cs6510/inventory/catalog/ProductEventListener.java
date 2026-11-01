package edu.northeastern.cs6510.inventory.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.inventory.messaging.DeadLetterPublisher;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Builds {@link CatalogNameProjection} from {@code catalog.products.v1} (005 research R3). Positions come
 * from the projection's own {@code topic_offset}, never from committed group offsets: on assignment
 * the listener seeks just past it (or to the beginning when there is none) and records the topic's
 * end offset as the catch-up target for readiness. Records at or below the stored offset are
 * skipped, so a redelivered batch applies nothing twice. A record that cannot be parsed or validated
 * is dead-lettered and skipped; the position still moves past it.
 */
@Component
public class ProductEventListener extends AbstractConsumerSeekAware implements DisposableBean {
  public static final String GROUP = "inventory-catalog-projection";
  private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);
  private static final TopicPartition PARTITION = new TopicPartition(ProductEventV1.TOPIC, 0);

  private final CatalogNameProjection projection;
  private final DeadLetterPublisher deadLetters;
  private final ObjectMapper json;
  private final AdminClient admin;
  private volatile boolean assigned;

  public ProductEventListener(CatalogNameProjection projection, DeadLetterPublisher deadLetters, ObjectMapper json,
                              @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    this.projection = projection;
    this.deadLetters = deadLetters;
    this.json = json;
    this.admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 2000, "default.api.timeout.ms", 3000));
  }

  @KafkaListener(id = "catalog-projection", groupId = GROUP, topics = ProductEventV1.TOPIC,
      containerFactory = "projectionListenerFactory")
  public void onBatch(List<ConsumerRecord<String, String>> records) {
    Long stored = projection.topicOffset();
    List<ProductEventV1> events = new ArrayList<>(records.size());
    long last = -1;
    for (ConsumerRecord<String, String> record : records) {
      if (stored != null && record.offset() <= stored) continue;
      last = record.offset();
      try {
        ProductEventV1 event = json.readValue(record.value(), ProductEventV1.class);
        event.validate();
        if (!record.key().equals(event.sku())) throw new IllegalArgumentException("record key " + record.key() + " is not the sku " + event.sku());
        events.add(event);
      } catch (Exception invalid) {
        log.warn("dead-lettering {}@{}: {}", record.topic(), record.offset(), invalid.toString());
        deadLetters.publish(record, invalid);
      }
    }
    if (last >= 0) projection.apply(events, last);
  }

  @Override
  public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
    super.onPartitionsAssigned(assignments, callback);
    if (!assignments.containsKey(PARTITION)) return;
    assigned = true;
    long end = endOffset();
    Long stored = projection.topicOffset();
    if (stored != null && end >= 0 && stored + 1 > end) {
      log.warn("stored {} offset {} is past the topic end {}; replaying from the beginning", ProductEventV1.TOPIC, stored, end);
      projection.forgetPosition();
      stored = null;
    }
    if (stored == null) callback.seekToBeginning(List.of(PARTITION));
    else callback.seek(PARTITION.topic(), PARTITION.partition(), stored + 1);
    if (end >= 0) projection.catchUpTo(end);
  }

  @Override
  public void onPartitionsRevoked(java.util.Collection<TopicPartition> partitions) {
    super.onPartitionsRevoked(partitions);
    if (partitions.contains(PARTITION)) assigned = false;
  }

  /** Retries the end-offset read when the broker was unreachable at assignment. */
  @Scheduled(initialDelay = 5000, fixedDelay = 5000)
  public void resolveCatchUpTarget() {
    if (!assigned || projection.catchUpTarget() >= 0) return;
    long end = endOffset();
    if (end >= 0) projection.catchUpTo(end);
  }

  /** Operator rebuild: drop the copy and replay the topic from the beginning. */
  public void rebuild() {
    projection.clear();
    seekToBeginning();
    long end = endOffset();
    if (end >= 0) projection.catchUpTo(end);
  }

  /** The partition's end offset, or -1 when the broker cannot be asked right now. */
  private long endOffset() {
    try {
      return admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest())).partitionResult(PARTITION).get(3, TimeUnit.SECONDS).offset();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    } catch (Exception e) {
      log.debug("cannot read the end offset of {}: {}", ProductEventV1.TOPIC, e.toString());
      return -1;
    }
  }

  @Override
  public void destroy() { admin.close(); }
}
