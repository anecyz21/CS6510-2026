package edu.northeastern.cs6510.checkout.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import edu.northeastern.cs6510.checkout.recovery.RecoveryProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The saga driver against in-memory fakes: transitions, lost races, leases, and no remote call inside a transaction (T042). */
class AttemptDriverTest {
  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(5);
  private static final String OWNER = "request-1";

  private FlagTransactions transactions;
  private InMemoryAttemptRepository attempts;
  private FakeInventory inventory;
  private AttemptDriver driver;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    transactions = new FlagTransactions();
    attempts = new InMemoryAttemptRepository(transactions::inside, clock);
    inventory = new FakeInventory(transactions::inside);
    driver = new AttemptDriver(attempts, inventory, transactions, clock, FaultInjector.NONE, RecoveryProperties.defaults(), d -> { });
  }

  @Test
  void fullReservationIsCommittedAndFinalized() {
    add("a-1", line("SKU-A", 2), line("SKU-B", 1));
    inventory.grants = Map.of("SKU-A", 2, "SKU-B", 1);

    assertEquals(AttemptDriver.Outcome.FINALIZED, driver.drive("a-1", DEADLINE, OWNER));
    assertEquals(AttemptState.FINALIZED, attempts.get("a-1").state());
    assertEquals(Map.of("SKU-A", 2, "SKU-B", 1), reserved("a-1"));
    assertEquals(List.of("reserve a-1", "commit a-1"), inventory.calls);
    assertTrue(attempts.finalized.contains("a-1"));
  }

  @Test
  void partialReservationStoresGrantedQuantitiesAsTheReceiptIntent() {
    add("a-1", line("SKU-A", 3), line("SKU-B", 1));
    inventory.grants = Map.of("SKU-A", 2, "SKU-B", 1);

    assertEquals(AttemptDriver.Outcome.FINALIZED, driver.drive("a-1", DEADLINE, OWNER));
    assertEquals(Map.of("SKU-A", 2, "SKU-B", 1), reserved("a-1"));
  }

  @Test
  void heldLeaseDriveFinalizesWithoutReadsOrLeaseWrites() {
    add("a-1", line("SKU-A", 2), line("SKU-B", 1));
    attempts.holdLease("a-1", OWNER, NOW.plusSeconds(10));   // as created by the freeze
    inventory.grants = Map.of("SKU-A", 1, "SKU-B", 1);

    AttemptDriver.Result result = driver.driveHeld(attempts.get("a-1"), DEADLINE, OWNER);
    assertEquals(AttemptDriver.Outcome.FINALIZED, result.outcome());
    assertEquals(NOW, result.attempt().finalizedAt(), "the finalize time travels back for the receipt");
    assertEquals(Map.of("SKU-A", 1, "SKU-B", 1), result.attempt().lines().stream()
        .collect(java.util.stream.Collectors.toMap(AttemptLine::sku, AttemptLine::reservedQuantity)));
    assertEquals(0, attempts.finds, "the driver carries the attempt instead of re-reading it");
    assertEquals(0, attempts.leaseWrites, "a terminal attempt needs no lease release");
    assertEquals(List.of("reserve a-1", "commit a-1"), inventory.calls);
  }

  @Test
  void heldLeaseDriveReleasesTheLeaseWhenUnresolved() {
    add("a-1", line("SKU-A", 1));
    attempts.holdLease("a-1", OWNER, NOW.plusSeconds(10));
    inventory.unavailable = true;

    assertEquals(AttemptDriver.Outcome.UNRESOLVED, driver.driveHeld(attempts.get("a-1"), DEADLINE, OWNER).outcome());
    assertEquals(1, attempts.leaseWrites, "recovery must be able to take the attempt over");
  }

  @Test
  void allZeroReservationIsRejectedAndUnfreezesTheBasket() {
    add("a-1", line("SKU-A", 3));
    inventory.grants = Map.of("SKU-A", 0);

    assertEquals(AttemptDriver.Outcome.REJECTED, driver.drive("a-1", DEADLINE, OWNER));
    assertTrue(attempts.cleared.contains("a-1"));
    assertEquals(List.of("reserve a-1"), inventory.calls);
  }

  @Test
  void cancelledTombstoneEndsTheAttemptWithoutCommitting() {
    add("a-1", line("SKU-A", 1));
    inventory.tombstone = true;

    assertEquals(AttemptDriver.Outcome.CANCELLED, driver.drive("a-1", DEADLINE, OWNER));
    assertTrue(attempts.cleared.contains("a-1"));
    assertEquals(List.of("reserve a-1"), inventory.calls);
  }

  @Test
  void lostTransitionRaceReReadsAndContinuesFromTheWinnersState() {
    add("a-1", line("SKU-A", 2));
    inventory.grants = Map.of("SKU-A", 2);
    attempts.loseNextTransition = true;   // another driver prepares the attempt first

    assertEquals(AttemptDriver.Outcome.FINALIZED, driver.drive("a-1", DEADLINE, OWNER));
    assertEquals(List.of("reserve a-1", "commit a-1"), inventory.calls);
  }

  @Test
  void unknownInventoryOutcomeIsRetriedThenLeftRecoverable() {
    add("a-1", line("SKU-A", 1));
    inventory.unavailable = true;

    assertEquals(AttemptDriver.Outcome.UNRESOLVED, driver.drive("a-1", DEADLINE, OWNER));
    assertEquals(4, inventory.count("reserve"), "one call plus three in-request retries of the same command");
    CompletionAttempt attempt = attempts.get("a-1");
    assertEquals(AttemptState.RESERVING, attempt.state());
    assertEquals(1, attempt.stepFailures());
    assertEquals(NOW.plusMillis(250), attempt.nextRetryAt(), "first recovery backoff is 250 ms");
    assertFalse(attempts.cleared.contains("a-1"), "an unconfirmed attempt keeps the basket frozen");
  }

  @Test
  void noInventoryCallStartsWhenItCannotFinishBeforeTheDeadline() {
    add("a-1", line("SKU-A", 1));
    assertEquals(AttemptDriver.Outcome.UNRESOLVED, driver.drive("a-1", NOW.plusMillis(500), OWNER));
    assertEquals(List.of(), inventory.calls);
    assertEquals(0, attempts.get("a-1").stepFailures(), "running out of time is not a failure");
  }

  @Test
  void anotherOwnersLeaseMakesTheRequestWaitInsteadOfDriving() {
    // The waiting request polls until its deadline, so the fake sleep must move time forward.
    Instant[] now = {NOW};
    Clock moving = new Clock() {
      @Override public Instant instant() { return now[0]; }
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    };
    AttemptDriver waiting = new AttemptDriver(attempts, inventory, transactions, moving, FaultInjector.NONE,
        RecoveryProperties.defaults(), d -> now[0] = now[0].plus(d));
    add("a-1", line("SKU-A", 1));
    attempts.holdLease("a-1", "recovery-1", NOW.plusSeconds(10));

    assertEquals(AttemptDriver.Outcome.UNRESOLVED, waiting.drive("a-1", DEADLINE, OWNER));
    assertEquals(List.of(), inventory.calls);
    assertTrue(!now[0].isAfter(DEADLINE), "the wait ends at the deadline");
  }

  @Test
  void cancellingAttemptIsReleasedAndUnfreezesTheBasket() {
    attempts.add("a-1", "tx-1", AttemptState.CANCELLING, NOW, 1, line("SKU-A", 1));

    assertEquals(AttemptDriver.Outcome.CANCELLED, driver.drive("a-1", DEADLINE, OWNER));
    assertEquals(List.of("cancel a-1"), inventory.calls);
    assertTrue(attempts.cleared.contains("a-1"));
  }

  @Test
  void cancelThatFindsACommittedReservationIsParkedForOperators() {
    attempts.add("a-1", "tx-1", AttemptState.CANCELLING, NOW, 1, line("SKU-A", 1));
    inventory.cancelSeesCommitted = true;

    assertEquals(AttemptDriver.Outcome.UNRESOLVED, driver.drive("a-1", DEADLINE, OWNER));
    assertTrue(attempts.get("a-1").needsAttention());
    assertTrue(attempts.parked.get(0).contains("cancel observed COMMITTED"));
    assertEquals(AttemptState.CANCELLING, attempts.get("a-1").state());
  }

  @Test
  void mismatchedReservationLinesAreParked() {
    add("a-1", line("SKU-A", 1));
    inventory.grants = Map.of("SKU-OTHER", 1);

    assertEquals(AttemptDriver.Outcome.UNRESOLVED, driver.drive("a-1", DEADLINE, OWNER));
    assertTrue(attempts.get("a-1").needsAttention());
  }

  @Test
  void neverCallsInventoryInsideALocalTransaction() {
    add("a-1", line("SKU-A", 2), line("SKU-B", 1));
    inventory.grants = Map.of("SKU-A", 1, "SKU-B", 1);
    driver.drive("a-1", DEADLINE, OWNER);
    assertFalse(inventory.calledInsideTransaction);
    assertEquals(2, inventory.calls.size());
  }

  private void add(String attemptId, AttemptLine... lines) {
    attempts.add(attemptId, "tx-1", AttemptState.RESERVING, NOW, 0, lines);
  }

  private Map<String, Integer> reserved(String attemptId) {
    Map<String, Integer> out = new HashMap<>();
    for (AttemptLine l : attempts.get(attemptId).lines()) out.put(l.sku(), l.reservedQuantity());
    return out;
  }

  static AttemptLine line(String sku, int quantity) { return new AttemptLine(sku, quantity, 100, "Item " + sku, null); }
}
