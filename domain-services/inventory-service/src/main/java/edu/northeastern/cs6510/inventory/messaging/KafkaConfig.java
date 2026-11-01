package edu.northeastern.cs6510.inventory.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Inventory's Kafka wiring (005 research R1, R4, R8): the reply producer and the topics Inventory
 * owns ({@code inventory.replies.v1}, {@code inventory.dlt.v1}), and the listener factories for
 * reservation commands (one record at a time) and the catalog projection (batches). Offsets are
 * committed only after the listener's local transaction commits. Startup does not wait for Kafka.
 */
@Configuration
public class KafkaConfig {
  /** Reservation-command listener threads: below the 12 partitions and the DB pool of 10. */
  public static final int COMMAND_CONCURRENCY = 6;

  @Bean
  public ProducerFactory<String, String> replyProducerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    return new DefaultKafkaProducerFactory<>(Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
        ProducerConfig.ACKS_CONFIG, "all",
        // Every reply is on a shopper's request path: send at once, never wait to fill a batch.
        ProducerConfig.LINGER_MS_CONFIG, 0,
        ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,
        ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
  }

  @Bean
  public KafkaTemplate<String, String> replyTemplate(ProducerFactory<String, String> replyProducerFactory) {
    return new KafkaTemplate<>(replyProducerFactory);
  }

  @Bean
  public NewTopic repliesTopic() {
    return new NewTopic(InventoryReplyV1.TOPIC, 12, (short) 1).configs(Map.of(
        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
        TopicConfig.RETENTION_MS_CONFIG, Long.toString(TimeUnit.DAYS.toMillis(7)),
        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1"));
  }

  @Bean
  public NewTopic deadLetterTopic() {
    return new NewTopic(DeadLetterPublisher.TOPIC, 1, (short) 1).configs(Map.of(
        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
        TopicConfig.RETENTION_MS_CONFIG, Long.toString(TimeUnit.DAYS.toMillis(14))));
  }

  @Bean
  public KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    KafkaAdmin admin = new KafkaAdmin(Map.of(
        "bootstrap.servers", bootstrap,
        "request.timeout.ms", 3000,
        "default.api.timeout.ms", 5000));
    admin.setFatalIfBrokerNotAvailable(false);
    admin.setAutoCreate(true);
    admin.setOperationTimeout(5);
    return admin;
  }

  @Bean
  public ConsumerFactory<String, String> messagingConsumerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  /**
   * Retries a failed record 3 more times (200 ms doubling, 5 s cap), then dead-letters it. A record
   * that cannot be parsed or validated is dead-lettered at once. A database outage is retried
   * without limit so the listener waits instead of dead-lettering work that would succeed later.
   */
  @Bean
  public DefaultErrorHandler messagingErrorHandler(DeadLetterPublisher deadLetters) {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
    backOff.setInitialInterval(200);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(5000);
    DefaultErrorHandler handler = new DefaultErrorHandler((record, e) -> deadLetters.publish(cast(record), e), backOff);
    handler.addNotRetryableExceptions(IllegalArgumentException.class, JsonProcessingException.class);
    handler.setBackOffFunction((record, e) -> healing(e) ? new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS) : null);
    return handler;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> commandListenerFactory(
      ConsumerFactory<String, String> messagingConsumerFactory, DefaultErrorHandler messagingErrorHandler) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(messagingConsumerFactory);
    factory.setConcurrency(COMMAND_CONCURRENCY);
    factory.getContainerProperties().setAckMode(AckMode.RECORD);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    factory.setCommonErrorHandler(messagingErrorHandler);
    return factory;
  }

  /** The catalog projection stores its own position; Kafka offsets are only for monitoring. */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> projectionListenerFactory(
      ConsumerFactory<String, String> messagingConsumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(messagingConsumerFactory);
    factory.setBatchListener(true);
    factory.setConcurrency(1);
    factory.getContainerProperties().setAckMode(AckMode.BATCH);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    // A failed batch is redelivered; records at or below the stored position are skipped.
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS)));
    return factory;
  }

  static boolean healing(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof TransientDataAccessException || t instanceof DataAccessResourceFailureException
          || t instanceof CannotCreateTransactionException) return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static org.apache.kafka.clients.consumer.ConsumerRecord<String, String> cast(
      org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record) {
    return (org.apache.kafka.clients.consumer.ConsumerRecord<String, String>) record;
  }
}
