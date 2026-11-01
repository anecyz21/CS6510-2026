package edu.northeastern.cs6510.checkout.health;

import edu.northeastern.cs6510.checkout.messaging.DeadLetterPublisher;
import edu.northeastern.cs6510.checkout.messaging.OutboxSender;
import edu.northeastern.cs6510.checkout.outbox.OutboxRelay;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Checkout's message backlogs as detail under {@code outbox}: unsent accepted scans, unsent Inventory
 * commands (005), and records this service has dead-lettered since it started. Always UP: a backlog
 * is not an outage (FR-025), and the sweeps drain it once the broker is back.
 */
@Component
public class OutboxHealthIndicator implements HealthIndicator {
  private final OutboxRelay relay;
  private final OutboxSender commands;
  private final DeadLetterPublisher deadLetters;

  public OutboxHealthIndicator(OutboxRelay relay, OutboxSender commands, DeadLetterPublisher deadLetters) {
    this.relay = relay;
    this.commands = commands;
    this.deadLetters = deadLetters;
  }

  @Override
  public Health health() {
    return Health.up()
        .withDetail("outboxPending", relay.pending())
        .withDetail("commandOutboxPending", commands.pending())
        .withDetail("deadLetters", (long) deadLetters.published())
        .build();
  }
}
