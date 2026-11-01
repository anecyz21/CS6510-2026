package edu.northeastern.cs6510.analytics.messaging;

import edu.northeastern.cs6510.contracts.messaging.MessageHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves a record that cannot be processed to {@code analytics.dlt.v1}, Analytics' own dead-letter
 * topic (005 contracts/README.md "Retries and dead-letter topics"). The record keeps its key,
 * value and headers and gains the {@code dlt-*} headers. The send is synchronous: a failure throws,
 * so the caller does not acknowledge and the record is retried instead of lost.
 */
@Component
public class DeadLetterPublisher implements DisposableBean {
  public static final String TOPIC = "analytics.dlt.v1";

  private final KafkaTemplate<String, String> template;
  private final MeterRegistry meters;
  private final AdminClient admin;

  public DeadLetterPublisher(@Value("${spring.kafka.bootstrap-servers}") String bootstrap, MeterRegistry meters) {
    this.template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
        ProducerConfig.ACKS_CONFIG, "all",
        ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    this.meters = meters;
    this.admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 2000, "default.api.timeout.ms", 3000));
  }

  public void publish(ConsumerRecord<String, String> record, Throwable cause) {
    Throwable root = rootCause(cause);
    ProducerRecord<String, String> out = new ProducerRecord<>(TOPIC, 0, record.key(), record.value());
    record.headers().forEach(h -> out.headers().add(h));
    add(out, MessageHeaders.DLT_ORIGINAL_TOPIC, record.topic());
    add(out, MessageHeaders.DLT_ORIGINAL_PARTITION, Integer.toString(record.partition()));
    add(out, MessageHeaders.DLT_ORIGINAL_OFFSET, Long.toString(record.offset()));
    add(out, MessageHeaders.DLT_EXCEPTION_CLASS, root.getClass().getName());
    add(out, MessageHeaders.DLT_EXCEPTION_MESSAGE, String.valueOf(root.getMessage()));
    try {
      template.send(out).get(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KafkaException("interrupted while dead-lettering " + record.topic() + "@" + record.offset(), e);
    } catch (ExecutionException | TimeoutException e) {
      throw new KafkaException("could not dead-letter " + record.topic() + "@" + record.offset(), e);
    }
    meters.counter("messaging.dlt.published", "topic", record.topic()).increment();
  }

  /**
   * Records currently in this service's dead-letter topic (end minus start offset), read from the
   * broker so it survives restarts; -1 when the broker cannot be asked. For reconciliation.
   */
  public long topicRecords() {
    TopicPartition partition = new TopicPartition(TOPIC, 0);
    try {
      long end = admin.listOffsets(Map.of(partition, OffsetSpec.latest())).partitionResult(partition).get(3, TimeUnit.SECONDS).offset();
      long start = admin.listOffsets(Map.of(partition, OffsetSpec.earliest())).partitionResult(partition).get(3, TimeUnit.SECONDS).offset();
      return end - start;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    } catch (Exception e) {
      return -1;
    }
  }

  @Override
  public void destroy() { admin.close(); }

  public double published() {
    var counters = meters.find("messaging.dlt.published").counters();
    return counters.stream().mapToDouble(c -> c.count()).sum();
  }

  private static void add(ProducerRecord<String, String> out, String name, String value) {
    out.headers().remove(name);
    out.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  static Throwable rootCause(Throwable t) {
    Throwable current = t;
    while (current.getCause() != null && current.getCause() != current) current = current.getCause();
    return current;
  }
}
