package edu.northeastern.cs6510.catalog.health;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the broker is reachable, under {@code kafka}. Part of readiness (005 research R9),
 * which only orders startup and feeds monitoring: requests keep being served while Kafka is down.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator, DisposableBean {
  private final AdminClient admin;

  public KafkaHealthIndicator(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    this.admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 500, "default.api.timeout.ms", 1000));
  }

  @Override
  public Health health() {
    try {
      int brokers = admin.describeCluster(new DescribeClusterOptions().timeoutMs(500)).nodes().get(1, TimeUnit.SECONDS).size();
      return Health.up().withDetail("brokers", brokers).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Health.down().withDetail("error", "interrupted").build();
    } catch (Exception e) {
      return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
    }
  }

  @Override
  public void destroy() { admin.close(); }
}
