package edu.northeastern.cs6510.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.northeastern.cs6510.support.AnalyticsOps;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.ScanDriver;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V8.3 (SC-007, FR-025): scans keep being accepted while Analytics and then Kafka are
 * down, and every one of them is ingested exactly once after both return.
 */
@Tag("system")
class OutageRecoveryTest {
  private static final int THREADS = 8;

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void scansAcceptedDuringAnalyticsAndKafkaOutagesAreAllIngested() throws Exception {
    AnalyticsOps.Status startStatus = AnalyticsOps.awaitDrained(Duration.ofSeconds(120));
    long startTotal = AnalyticsOps.outbox().total();
    try {
      Stack.stop("analytics");
      assertAllAccepted(ScanDriver.parallel(5000, THREADS, OutageRecoveryTest::skuFor), 5000);
      Stack.stop("kafka");
      assertAllAccepted(ScanDriver.parallel(5000, THREADS, OutageRecoveryTest::skuFor), 5000);
    } finally {
      Stack.start("kafka");
      Stack.awaitHealthy("kafka", Duration.ofSeconds(120));
      Stack.start("analytics");
      Stack.awaitReady("analytics", Duration.ofSeconds(120));
    }
    assertAllAccepted(ScanDriver.parallel(1000, THREADS, OutageRecoveryTest::skuFor), 1000);

    AnalyticsOps.Status settled = AnalyticsOps.awaitDrained(Duration.ofSeconds(300));
    AnalyticsOps.Outbox outbox = AnalyticsOps.outbox();
    assertEquals(11_000, outbox.total() - startTotal, "every accepted scan is in the outbox");
    assertEquals(11_000, settled.ingestedCount() - startStatus.ingestedCount(), "every accepted scan is ingested once");
    assertEquals(outbox.total(), settled.ingestedCount());
    Reconciliation.assertPass();
  }

  private static String skuFor(int i) { return String.format("SKU-%06d", 1 + (i % 50)); }

  private static void assertAllAccepted(ScanDriver.Outcome outcome, int expected) {
    assertEquals(0, outcome.nonOk(), "every start and scan answers 200: " + outcome.statuses());
    assertEquals(expected, outcome.accepted());
  }
}
