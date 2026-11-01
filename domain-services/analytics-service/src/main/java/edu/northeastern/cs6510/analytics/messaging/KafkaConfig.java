package edu.northeastern.cs6510.analytics.messaging;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Analytics' Kafka wiring added by 005 (research R1, R3, R8): its dead-letter topic
 * {@code analytics.dlt.v1} and the batch listener factory for the catalog name projection. The
 * accepted-scan consumer keeps its own factory in {@code ingest.KafkaConsumerConfig}.
 */
@Configuration
public class KafkaConfig {

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
  public ConsumerFactory<String, String> projectionConsumerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  /** The catalog projection stores its own position; Kafka offsets are only for monitoring. */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> projectionListenerFactory(
      ConsumerFactory<String, String> projectionConsumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(projectionConsumerFactory);
    factory.setBatchListener(true);
    factory.setConcurrency(1);
    factory.getContainerProperties().setAckMode(AckMode.BATCH);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    // A failed batch is redelivered; records at or below the stored position are skipped.
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS)));
    return factory;
  }
}
