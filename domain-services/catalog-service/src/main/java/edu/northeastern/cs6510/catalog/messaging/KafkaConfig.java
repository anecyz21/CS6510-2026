package edu.northeastern.cs6510.catalog.messaging;

import edu.northeastern.cs6510.contracts.catalog.v1.ProductEventV1;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Producer and topic for {@code catalog.products.v1} (005 research R1, R2). Catalog owns the topic.
 * It is compacted so the latest record per SKU stays forever and any consumer can rebuild a full
 * copy by replaying from the beginning. Startup does not wait for Kafka: the publisher retries.
 */
@Configuration
public class KafkaConfig {

  @Bean
  public ProducerFactory<String, String> productProducerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    return new DefaultKafkaProducerFactory<>(Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
        ProducerConfig.ACKS_CONFIG, "all",
        ProducerConfig.LINGER_MS_CONFIG, 0,
        ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
  }

  @Bean
  public KafkaTemplate<String, String> productTemplate(ProducerFactory<String, String> productProducerFactory) {
    return new KafkaTemplate<>(productProducerFactory);
  }

  /** One partition: each consumer stores one offset and compares it with one end offset at startup. */
  @Bean
  public NewTopic productsTopic() {
    return new NewTopic(ProductEventV1.TOPIC, 1, (short) 1).configs(Map.of(
        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT,
        TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, "0",
        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1"));
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
}
