package edu.northeastern.cs6510.checkout.health;

import edu.northeastern.cs6510.checkout.outbox.OutboxRelay;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** The accepted-scan backlog as detail under {@code outbox}; always UP, because a backlog is not an outage (FR-025). */
@Component
public class OutboxHealthIndicator implements HealthIndicator {
  private final OutboxRelay relay;

  public OutboxHealthIndicator(OutboxRelay relay) { this.relay = relay; }

  @Override
  public Health health() { return Health.up().withDetail("outboxPending", relay.pending()).build(); }
}
