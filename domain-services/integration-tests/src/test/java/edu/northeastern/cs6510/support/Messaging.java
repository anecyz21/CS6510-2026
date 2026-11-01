package edu.northeastern.cs6510.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryCommandV1;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Talks to the stack's broker (localhost:9092) as a test operator would (005 quickstart): sends
 * Inventory commands and waits for their replies, or writes raw records for poison-message tests.
 * Tests use it instead of the reservation HTTP API, which no longer exists.
 */
public final class Messaging {
  public static final String BOOTSTRAP = System.getenv().getOrDefault("KAFKA_HOST_BOOTSTRAP", "localhost:9092");
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private Messaging() { }

  /** Sends a reserve command for one line and returns Inventory's reply. */
  public static InventoryReplyV1 reserve(String attemptId, String sku, int quantity) {
    return roundTrip(InventoryCommandV1.reserve(UUID.randomUUID().toString(), attemptId, UUID.randomUUID().toString(),
        List.of(new ReserveLineV1(sku, quantity))));
  }

  public static InventoryReplyV1 cancel(String attemptId) {
    return roundTrip(InventoryCommandV1.cancel(UUID.randomUUID().toString(), attemptId));
  }

  /** Writes {@code value} to {@code topic} with a valid envelope, whatever the value holds. */
  public static void sendRaw(String topic, String key, String value, String messageType) {
    try (KafkaProducer<String, String> producer = producer()) {
      ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
      new Envelope(UUID.randomUUID().toString(), messageType, 1, "test", Instant.now(), key, null).toHeaders()
          .forEach((k, v) -> record.headers().add(k, v));
      producer.send(record).get();
    } catch (Exception e) {
      throw new IllegalStateException("cannot write to " + topic, e);
    }
  }

  /** Number of records in a single-partition topic such as a dead-letter topic. */
  public static long recordCount(String topic) {
    try (KafkaConsumer<String, String> consumer = consumer("test-count-" + UUID.randomUUID())) {
      var partition = new org.apache.kafka.common.TopicPartition(topic, 0);
      var offsets = consumer.endOffsets(List.of(partition));
      var begin = consumer.beginningOffsets(List.of(partition));
      return offsets.getOrDefault(partition, 0L) - begin.getOrDefault(partition, 0L);
    }
  }

  /**
   * Deletes every record now in a single-partition topic (moves its start offset to the end), so a
   * test that dead-letters on purpose leaves the dead-letter topics empty for reconciliation R-8.
   */
  public static void purge(String topic) {
    try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP))) {
      var partition = new org.apache.kafka.common.TopicPartition(topic, 0);
      long end = admin.listOffsets(Map.of(partition, org.apache.kafka.clients.admin.OffsetSpec.latest()))
          .partitionResult(partition).get().offset();
      admin.deleteRecords(Map.of(partition, org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(end))).all().get();
    } catch (Exception e) {
      throw new IllegalStateException("cannot purge " + topic, e);
    }
  }

  private static InventoryReplyV1 roundTrip(InventoryCommandV1 command) {
    try (KafkaConsumer<String, String> replies = consumer("test-replies-" + UUID.randomUUID());
         KafkaProducer<String, String> producer = producer()) {
      replies.subscribe(List.of(InventoryReplyV1.TOPIC));
      replies.poll(Duration.ofMillis(500));   // join the group before sending, so the reply is not missed
      ProducerRecord<String, String> record = new ProducerRecord<>(InventoryCommandV1.TOPIC, command.attemptId(), JSON.writeValueAsString(command));
      new Envelope(command.messageId(), command.messageType(), 1, "checkout", Instant.now(), command.attemptId(), null).toHeaders()
          .forEach((k, v) -> record.headers().add(k, v));
      producer.send(record).get();
      Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
      while (Instant.now().isBefore(deadline)) {
        for (ConsumerRecord<String, String> r : replies.poll(Duration.ofMillis(200))) {
          InventoryReplyV1 reply = JSON.readValue(r.value(), InventoryReplyV1.class);
          if (command.attemptId().equals(reply.attemptId()) && command.messageType().equals(reply.command())) return reply;
        }
      }
      throw new AssertionError("no reply to " + command.messageType() + " for " + command.attemptId() + " within 30 s");
    } catch (AssertionError e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static KafkaProducer<String, String> producer() {
    return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
  }

  private static KafkaConsumer<String, String> consumer(String group) {
    return new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP, ConsumerConfig.GROUP_ID_CONFIG, group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
  }
}
