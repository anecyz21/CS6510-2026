package edu.northeastern.cs6510.analytics.health;

import edu.northeastern.cs6510.analytics.catalog.CatalogNameProjection;
import edu.northeastern.cs6510.analytics.ops.ConsumerLag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Detail-only health for the ingest consumer ({@code kafkaConsumer}) and the name projection
 * ({@code catalogProjection}). Both stay outside readiness: Analytics serves its last published
 * window while Kafka or Catalog is away.
 */
@Configuration
public class AnalyticsHealthIndicators {

  @Bean
  HealthIndicator kafkaConsumerHealthIndicator(KafkaListenerEndpointRegistry registry, ConsumerLag lag) {
    return () -> {
      MessageListenerContainer container = registry.getListenerContainer("accepted-scan-ingest");
      boolean running = container != null && container.isRunning();
      boolean reachable = lag.brokerReachable();
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("listenerRunning", running);
      details.put("broker", reachable ? "UP" : "DOWN");
      return (running && reachable ? Health.up() : Health.down()).withDetails(details).build();
    };
  }

  @Bean
  HealthIndicator catalogProjectionHealthIndicator(CatalogNameProjection projection) {
    return () -> {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("state", projection.state().name());
      details.put("catalogVersion", projection.loadedVersion());
      details.put("lastVerifiedAt", projection.lastVerifiedAt() == null ? null : projection.lastVerifiedAt().toString());
      return Health.up().withDetails(details).build();
    };
  }
}
