package edu.northeastern.cs6510.checkout.outbox;

import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
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
 * Producer and topic for {@code checkout.accepted-scans.v1} (research R3, contracts/accepted-scan-event.md).
 * Checkout owns the topic. Nothing here blocks startup or readiness when Kafka is down: the relay
 * keeps events pending in the outbox until the broker is back (US3-1, FR-025).
 */
@Configuration
public class KafkaProducerConfig {

  @Bean
  public ProducerFactory<String, String> acceptedScanProducerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    return new DefaultKafkaProducerFactory<>(Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
        ProducerConfig.ACKS_CONFIG, "all",
        // Fail fast when the broker is unreachable instead of blocking the relay for a minute.
        ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
  }

  @Bean
  public KafkaTemplate<String, String> acceptedScanTemplate(ProducerFactory<String, String> acceptedScanProducerFactory) {
    return new KafkaTemplate<>(acceptedScanProducerFactory);
  }

  /** One partition: one total order, which is the canonical input order for Analytics windows. */
  @Bean
  public NewTopic acceptedScansTopic() {
    return new NewTopic(AcceptedScanV1.TOPIC, 1, (short) 1).configs(Map.of(
        TopicConfig.RETENTION_MS_CONFIG, "604800000",
        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
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
