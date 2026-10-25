package edu.northeastern.cs6510.checkout.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.checkout.completion.AttemptDriver;
import edu.northeastern.cs6510.checkout.completion.AttemptLine;
import edu.northeastern.cs6510.checkout.completion.AttemptState;
import edu.northeastern.cs6510.checkout.completion.CompletionProperties;
import edu.northeastern.cs6510.checkout.completion.FakeInventory;
import edu.northeastern.cs6510.checkout.completion.FlagTransactions;
import edu.northeastern.cs6510.checkout.completion.InMemoryAttemptRepository;
import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Recovery scans against fakes and a controllable clock (T064). In-request retries are off so each scan is one failed step. */
class RecoveryWorkerTest {
  private static final Instant T0 = Instant.parse("2026-10-25T12:00:00Z");

  private MutableClock clock;
  private InMemoryAttemptRepository attempts;
  private FakeInventory inventory;
  private RecoveryWorker worker;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    FlagTransactions transactions = new FlagTransactions();
    attempts = new InMemoryAttemptRepository(transactions::inside, clock);
    inventory = new FakeInventory(transactions::inside);
    RecoveryProperties recovery = new RecoveryProperties(null, null, null, null, null, null, 0, null);
    AttemptDriver driver = new AttemptDriver(attempts, inventory, transactions, clock, FaultInjector.NONE, recovery, d -> { });
    worker = new RecoveryWorker(attempts, driver, transactions, recovery, CompletionProperties.defaults(),
        new SimpleMeterRegistry(), clock, "recovery-1");
  }

  @Test
  void attemptLeasedByAnotherDriverIsSkippedUntilTheLeaseExpires() {
    reserving("a-1", 0);
    inventory.grants = Map.of("SKU-A", 1);
    attempts.holdLease("a-1", "request-7", T0.plusSeconds(10));

    worker.scan();
    assertEquals(List.of(), inventory.calls);

    clock.advance(Duration.ofSeconds(11));
    worker.scan();
    assertEquals(AttemptState.FINALIZED, attempts.get("a-1").state());
  }

  @Test
  void failedStepsBackOffExponentiallyUpToTheCap() {
    reserving("a-1", 0);
    inventory.unavailable = true;
    List<Long> delays = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      worker.scan();
      Instant next = attempts.get("a-1").nextRetryAt();
      delays.add(Duration.between(clock.instant(), next).toMillis());
      clock.set(next);
    }
    assertEquals(List.of(250L, 500L, 1000L, 2000L, 4000L, 5000L, 5000L), delays);
  }

  @Test
  void reservingAttemptOlderThanCancelAfterIsCancelledAfterAFailure() {
    reserving("a-1", 1);
    clock.advance(Duration.ofSeconds(31));

    worker.scan();
    assertEquals(AttemptState.CANCELLED, attempts.get("a-1").state());
    assertEquals(List.of("cancel a-1"), inventory.calls);
  }

  @Test
  void reservingAttemptWithoutFailuresIsNeverCancelledForAge() {
    reserving("a-1", 0);
    inventory.grants = Map.of("SKU-A", 1);
    clock.advance(Duration.ofMinutes(5));

    worker.scan();
    assertEquals(AttemptState.FINALIZED, attempts.get("a-1").state());
    assertEquals(0, inventory.count("cancel"));
  }

  @Test
  void committingAttemptOnlyMovesForwardWhateverItsAge() {
    attempts.add("a-1", "tx-1", AttemptState.COMMITTING, T0, 5, prepared("SKU-A", 1));
    inventory.unavailable = true;
    clock.advance(Duration.ofMinutes(10));

    worker.scan();
    assertEquals(AttemptState.COMMITTING, attempts.get("a-1").state());
    assertEquals(0, inventory.count("cancel"));
    assertEquals(1, inventory.count("commit"));

    inventory.unavailable = false;
    clock.set(attempts.get("a-1").nextRetryAt());
    worker.scan();
    assertEquals(AttemptState.FINALIZED, attempts.get("a-1").state());
  }

  @Test
  void attemptIsFlaggedAfterTwentyFailuresAndStillRetried() {
    attempts.add("a-1", "tx-1", AttemptState.COMMITTING, T0, 0, prepared("SKU-A", 1));
    inventory.unavailable = true;
    for (int i = 0; i < 19; i++) {
      worker.scan();
      clock.set(attempts.get("a-1").nextRetryAt());
    }
    assertFalse(attempts.get("a-1").needsAttention());

    worker.scan();
    assertTrue(attempts.get("a-1").needsAttention(), "flagged at 20 consecutive failures");

    clock.set(attempts.get("a-1").nextRetryAt());
    worker.scan();
    assertEquals(21, inventory.count("commit"), "retrying continues after the flag");
  }

  @Test
  void cancelThatFindsACommittedReservationStopsAutomaticRetries() {
    attempts.add("a-1", "tx-1", AttemptState.CANCELLING, T0, 1, prepared("SKU-A", 1));
    inventory.cancelSeesCommitted = true;

    worker.scan();
    assertTrue(attempts.get("a-1").needsAttention());
    clock.advance(Duration.ofMinutes(10));
    worker.scan();
    assertEquals(1, inventory.count("cancel"), "a parked attempt waits for an operator");
  }

  private void reserving(String attemptId, int failures) {
    attempts.add(attemptId, "tx-1", AttemptState.RESERVING, T0, failures, new AttemptLine("SKU-A", 1, 100, "Item A", null));
  }

  private static AttemptLine prepared(String sku, int quantity) { return new AttemptLine(sku, quantity, 100, "Item " + sku, quantity); }

  /** A clock the test moves by hand. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) { this.now = now; }

    void advance(Duration d) { now = now.plus(d); }

    void set(Instant instant) { now = instant; }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
  }
}
