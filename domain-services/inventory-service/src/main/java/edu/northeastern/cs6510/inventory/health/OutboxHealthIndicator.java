package edu.northeastern.cs6510.inventory.health;

import edu.northeastern.cs6510.inventory.messaging.DeadLetterPublisher;
import edu.northeastern.cs6510.inventory.messaging.OutboxSender;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Inventory's reply backlog and dead-letter count as detail under {@code outbox} (005 research R5,
 * R8). Always UP: unsent replies wait in {@code reply_outbox} and the sweep sends them later.
 */
@Component
public class OutboxHealthIndicator implements HealthIndicator {
  private final OutboxSender replies;
  private final DeadLetterPublisher deadLetters;

  public OutboxHealthIndicator(OutboxSender replies, DeadLetterPublisher deadLetters) {
    this.replies = replies;
    this.deadLetters = deadLetters;
  }

  @Override
  public Health health() {
    return Health.up()
        .withDetail("replyOutboxPending", replies.pending())
        .withDetail("deadLetters", (long) deadLetters.published())
        .build();
  }
}
