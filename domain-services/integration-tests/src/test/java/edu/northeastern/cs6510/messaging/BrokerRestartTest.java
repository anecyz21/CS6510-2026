package edu.northeastern.cs6510.messaging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import edu.northeastern.cs6510.support.Workload;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 005 quickstart Q7, SC-008: the broker restarts under load. Scans keep succeeding from the local
 * catalog copy and completions answer 200 or 503. Within 60 s of the broker being healthy, every
 * outbox is drained, every consumer has caught up, and reconciliation passes.
 */
@Tag("system")
class BrokerRestartTest {
  private static final Set<String> ALLOWED = Set.of("START 201", "START 200", "SCAN 200", "COMPLETE 200", "COMPLETE 503");

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void nothingIsLostWhenKafkaRestartsMidRun() throws Exception {
    Workload workload = Workload.start(4, 3, "broker-restart");
    try {
      Thread.sleep(10_000);
      Stack.compose("restart", "kafka");
      Stack.awaitHealthy("kafka", Duration.ofSeconds(120));
      Thread.sleep(10_000);
    } finally {
      workload.close();
    }
    assertTrue(workload.count("SCAN 200") > 0, String.valueOf(workload.statuses()));
    for (String outcome : workload.statuses().keySet()) {
      assertTrue(ALLOWED.contains(outcome), "unexpected public outcome during a broker restart: " + workload.statuses());
    }
    Reconciliation.assertPass();   // waits up to 60 s for outboxes, attempts and consumers to settle
  }
}
