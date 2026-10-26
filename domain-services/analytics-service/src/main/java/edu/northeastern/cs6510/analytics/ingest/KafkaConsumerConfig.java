package edu.northeastern.cs6510.analytics.ingest;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Batch consumer for {@code checkout.accepted-scans.v1}. Offsets are committed only by the listener
 * after its local transaction commits; a failed batch is sought back and retried every second, so
 * nothing is skipped and the canonical order is kept (FR-022). Starts without Kafka (US3-1).
 */
@Configuration
public class KafkaConsumerConfig {
  public static final String GROUP = "analytics-scan-ingest";

  @Bean
  ConsumerFactory<String, String> acceptedScanConsumerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> acceptedScanListenerFactory(
      ConsumerFactory<String, String> acceptedScanConsumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(acceptedScanConsumerFactory);
    factory.setBatchListener(true);
    factory.setConcurrency(1);
    factory.getContainerProperties().setAckMode(AckMode.MANUAL);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS)));
    return factory;
  }
}
