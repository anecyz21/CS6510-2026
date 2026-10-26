package edu.northeastern.cs6510.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.northeastern.cs6510.support.AnalyticsOps;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.ScanDriver;
import edu.northeastern.cs6510.support.Stack;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quickstart V8.2 (FR-022, FR-024): replaying the whole topic and republishing the whole outbox
 * changes nothing. Every scan keeps its sequence number, and the windows, counts, ranks and
 * ingested count stay identical.
 */
@Tag("system")
class ReplayDuplicateTest {
  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void replayAndRepublishLeaveSequencesAndSnapshotsUnchanged() {
    if (AnalyticsOps.ingestStatus().ingestedCount() < 1000) {
      ScanDriver.sequential(new PublicApi(), 1000, KnownSequenceRankingTest::skuFor);
    }
    AnalyticsOps.Status before = AnalyticsOps.awaitDrained(Duration.ofSeconds(120));
    List<String> sequence = AnalyticsOps.ingestedSequence();
    List<AnalyticsOps.SnapshotItem> snapshots = AnalyticsOps.allSnapshotItems();
    List<long[]> windows = AnalyticsOps.snapshotWindows();
    assertTrue(windows.size() >= 2, "at least the windows at 1 and 500 exist");

    Stack.stop("analytics");
    resetGroupToEarliest();
    long republished = republishWholeOutbox();
    assertTrue(republished >= before.ingestedCount());
    Stack.start("analytics");
    Stack.awaitReady("analytics", Duration.ofSeconds(120));

    AnalyticsOps.Status after = AnalyticsOps.awaitDrained(Duration.ofSeconds(180));
    assertEquals(before.ingestedCount(), after.ingestedCount(), "duplicates are never ingested twice");
    assertEquals(before.lastSequence(), after.lastSequence());
    assertEquals(before.currentWindowEnd(), after.currentWindowEnd());
    assertEquals(sequence, AnalyticsOps.ingestedSequence(), "every scan keeps its sequence number");
    assertEquals(snapshots, AnalyticsOps.allSnapshotItems(), "windows, counts and ranks are identical");
    assertEquals(windows.size(), AnalyticsOps.snapshotWindows().size());
  }

  /** The consumer group must be empty, which it is shortly after Analytics stopped. */
  private static void resetGroupToEarliest() {
    AssertionError last = null;
    for (int attempt = 0; attempt < 10; attempt++) {
      try {
        Stack.compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:29092",
            "--group", "analytics-scan-ingest", "--topic", "checkout.accepted-scans.v1", "--reset-offsets", "--to-earliest", "--execute");
        return;
      } catch (AssertionError e) {
        last = e;
        KnownSequenceRankingTest.sleep(3000);
      }
    }
    fail("could not reset the analytics consumer group: " + last.getMessage());
  }

  private static long republishWholeOutbox() {
    try (Connection c = Stack.ownerConnection("checkout"); Statement s = c.createStatement()) {
      return s.executeUpdate("UPDATE accepted_scan_outbox SET published_at = NULL");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
