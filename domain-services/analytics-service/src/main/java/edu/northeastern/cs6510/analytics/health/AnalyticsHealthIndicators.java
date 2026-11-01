package edu.northeastern.cs6510.analytics.health;

import edu.northeastern.cs6510.analytics.catalog.CatalogNameProjection;
import edu.northeastern.cs6510.analytics.ops.ConsumerLag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Health for the ingest consumer ({@code kafkaConsumer}, detail only: Analytics serves its last
 * published window while Kafka is away) and the name projection ({@code catalogProjection}, part of
 * readiness since 005).
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

  /**
   * Readiness of the name copy (005 research R3, FR-007): {@code OUT_OF_SERVICE} while it replays
   * {@code catalog.products.v1}, {@code UP} once caught up. The popular-items view answers 503 itself
   * for a missing name, so readiness is the only thing this changes.
   */
  @Bean
  HealthIndicator catalogProjectionHealthIndicator(CatalogNameProjection projection) {
    return () -> {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("state", projection.state().name());
      details.put("names", projection.size());
      details.put("catalogVersion", projection.catalogVersion());
      details.put("topicOffset", projection.topicOffset());
      details.put("catchUpTarget", projection.catchUpTarget());
      return Health.status(projection.caughtUp() ? Status.UP : Status.OUT_OF_SERVICE).withDetails(details).build();
    };
  }
}
