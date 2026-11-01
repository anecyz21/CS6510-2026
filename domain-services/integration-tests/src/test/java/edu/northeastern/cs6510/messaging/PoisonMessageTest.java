package edu.northeastern.cs6510.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.northeastern.cs6510.contracts.inventory.v1.InventoryCommandV1;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.support.Messaging;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 005 quickstart Q8, FR-017: a record that cannot be processed lands once in the consuming
 * service's dead-letter topic, and the records after it on the same topic are still processed.
 */
@Tag("system")
class PoisonMessageTest {
  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  /** Leaves the dead-letter topics empty, so later reconciliations (R-8) are not affected. */
  @AfterEach
  void emptyDeadLetterTopics() {
    Messaging.purge("inventory.dlt.v1");
    Messaging.purge("checkout.dlt.v1");
  }

  @Test
  void aMalformedCommandIsDeadLetteredByInventoryAndTheNextCommandStillFlows() throws Exception {
    long before = Messaging.recordCount("inventory.dlt.v1");
    Messaging.sendRaw(InventoryCommandV1.TOPIC, "poison-" + UUID.randomUUID(), "{not json", InventoryCommandV1.RESERVE_REQUESTED);

    String attemptId = UUID.randomUUID().toString();
    assertEquals(InventoryReplyV1.CANCELLED, Messaging.cancel(attemptId).messageType(), "a later command is still answered");
    awaitCount("inventory.dlt.v1", before + 1);
  }

  @Test
  void aMalformedReplyIsDeadLetteredByCheckout() throws Exception {
    long before = Messaging.recordCount("checkout.dlt.v1");
    Messaging.sendRaw(InventoryReplyV1.TOPIC, "poison-" + UUID.randomUUID(), "{not json", InventoryReplyV1.RESERVED);
    awaitCount("checkout.dlt.v1", before + 1);
  }

  private static void awaitCount(String topic, long expected) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      if (Messaging.recordCount(topic) == expected) return;
      Thread.sleep(250);
    }
    assertEquals(expected, Messaging.recordCount(topic), topic + " record count");
  }
}
