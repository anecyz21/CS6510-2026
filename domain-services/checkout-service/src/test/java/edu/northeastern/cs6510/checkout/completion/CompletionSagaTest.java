package edu.northeastern.cs6510.checkout.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReplyErrorV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every row of the 005 data-model transition table, plus stale, duplicate and impossible replies (T021). */
class CompletionSagaTest {
  private static final Instant T0 = Instant.parse("2026-11-02T12:00:00Z");
  private static final AttemptLine A = new AttemptLine("SKU-A", 3, 250, "Apple", null);
  private static final AttemptLine B = new AttemptLine("SKU-B", 1, 100, "Bread", null);

  private MutableClock clock;
  private InMemoryAttemptRepository attempts;
  private RecordingCommands commands;
  private SimpleMeterRegistry meters;
  private CompletionSaga saga;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    FlagTransactions transactions = new FlagTransactions();
    attempts = new InMemoryAttemptRepository(transactions::inside, clock);
    commands = new RecordingCommands(transactions::inside);
    meters = new SimpleMeterRegistry();
    saga = new CompletionSaga(attempts, commands, new AttemptWaiters(attempts), transactions, FaultInjector.NONE, meters, clock);
  }

  @Test
  void reservedWhileReservingStoresTheGrantAndSendsCommit() {
    attempt("a-1", AttemptState.RESERVING);
    saga.onReply(reply("a-1", InventoryReplyV1.RESERVED, ReservationStateV1.RESERVED, 2, 1));

    CompletionAttempt after = attempts.get("a-1");
    assertEquals(AttemptState.COMMITTING, after.state());
    assertEquals(2, after.lines().get(0).reservedQuantity());
    assertEquals(1, after.lines().get(1).reservedQuantity());
    assertEquals(List.of("CommitRequested a-1"), commands.written);
    assertEquals(T0.plus(CompletionSaga.FIRST_RESEND_AFTER), after.nextRetryAt(), "a healthy saga is not resent");
  }

  @Test
  void releasedWhileReservingRejectsWithoutACommand() {
    attempt("a-1", AttemptState.RESERVING);
    saga.onReply(reply("a-1", InventoryReplyV1.RELEASED, ReservationStateV1.RELEASED, 0, 0));

    assertEquals(AttemptState.REJECTED, attempts.get("a-1").state());
    assertEquals(0, attempts.get("a-1").lines().get(0).reservedQuantity());
    assertTrue(attempts.cleared.contains("a-1"));
    assertEquals(List.of(), commands.written);
  }

  @Test
  void cancelledWhileReservingMeansTheTombstoneWon() {
    attempt("a-1", AttemptState.RESERVING);
    saga.onReply(reply("a-1", InventoryReplyV1.CANCELLED, ReservationStateV1.CANCELLED, 0, 0));

    assertEquals(AttemptState.CANCELLED, attempts.get("a-1").state());
    assertTrue(attempts.cleared.contains("a-1"));
    assertEquals(List.of(), commands.written);
  }

  @Test
  void committedWhileCommittingFinalizes() {
    attempt("a-1", AttemptState.COMMITTING);
    saga.onReply(reply("a-1", InventoryReplyV1.COMMITTED, ReservationStateV1.COMMITTED, 2, 1));

    assertEquals(AttemptState.FINALIZED, attempts.get("a-1").state());
    assertTrue(attempts.finalized.contains("a-1"));
    assertEquals(List.of(), commands.written);
  }

  @Test
  void releasedOrCancelledWhileCancellingCancels() {
    attempt("a-1", AttemptState.CANCELLING);
    attempt("a-2", AttemptState.CANCELLING);
    saga.onReply(reply("a-1", InventoryReplyV1.RELEASED, ReservationStateV1.RELEASED, 0, 0));
    saga.onReply(reply("a-2", InventoryReplyV1.CANCELLED, ReservationStateV1.CANCELLED, 0, 0));

    assertEquals(AttemptState.CANCELLED, attempts.get("a-1").state());
    assertEquals(AttemptState.CANCELLED, attempts.get("a-2").state());
    assertEquals(List.of(), commands.written);
  }

  @Test
  void aRepeatedReservedAfterCommittingChangesNothing() {
    attempt("a-1", AttemptState.RESERVING);
    InventoryReplyV1 reserved = reply("a-1", InventoryReplyV1.RESERVED, ReservationStateV1.RESERVED, 2, 1);
    saga.onReply(reserved);
    saga.onReply(reserved);

    assertEquals(AttemptState.COMMITTING, attempts.get("a-1").state());
    assertEquals(List.of("CommitRequested a-1"), commands.written, "the duplicate sends no second commit");
    assertEquals(1.0, ignored("stale"));
  }

  @Test
  void aReservedWhileCancellingIsIgnoredUntilTheCancelReply() {
    attempt("a-1", AttemptState.CANCELLING);
    saga.onReply(reply("a-1", InventoryReplyV1.RESERVED, ReservationStateV1.RESERVED, 2, 1));

    assertEquals(AttemptState.CANCELLING, attempts.get("a-1").state());
    assertEquals(1.0, ignored("stale"));
  }

  @Test
  void aRepliedAgainSettledAttemptIsIgnored() {
    attempt("a-1", AttemptState.COMMITTING);
    InventoryReplyV1 committed = reply("a-1", InventoryReplyV1.COMMITTED, ReservationStateV1.COMMITTED, 2, 1);
    saga.onReply(committed);
    saga.onReply(committed);

    assertEquals(1, attempts.finalized.size());
    assertEquals(1.0, ignored("settled"));
  }

  @Test
  void aReplyForAnUnknownAttemptIsIgnored() {
    saga.onReply(reply("nobody", InventoryReplyV1.RESERVED, ReservationStateV1.RESERVED, 1, 1));
    assertEquals(1.0, ignored("unknown-attempt"));
  }

  @Test
  void aConflictParksTheAttemptForOperators() {
    attempt("a-1", AttemptState.COMMITTING);
    InventoryReplyV1 conflict = new InventoryReplyV1(1, "m-9", InventoryReplyV1.CONFLICT, "a-1", "CommitRequested", null,
        new ReplyErrorV1("RESERVATION_NOT_FOUND", "Reservation a-1 does not exist."));
    saga.onReply(conflict);

    assertEquals(AttemptState.COMMITTING, attempts.get("a-1").state(), "no transition");
    assertTrue(attempts.get("a-1").needsAttention());
    assertTrue(attempts.parked.get(0).contains("RESERVATION_NOT_FOUND"));
  }

  @Test
  void reservedLinesThatDoNotMatchTheSnapshotParkTheAttempt() {
    attempt("a-1", AttemptState.RESERVING);
    ReservationV1 other = new ReservationV1("a-1", "tx-1", ReservationStateV1.RESERVED,
        List.of(new ReservationLineV1("SKU-A", 3, 3)), T0, null);
    saga.onReply(new InventoryReplyV1(1, "m-9", InventoryReplyV1.RESERVED, "a-1", "ReserveRequested", other, null));

    assertEquals(AttemptState.RESERVING, attempts.get("a-1").state());
    assertTrue(attempts.get("a-1").needsAttention());
    assertEquals(List.of(), commands.written);
  }

  @Test
  void resendWritesTheCurrentStatesCommandOnce() {
    attempts.add("a-1", "tx-1", AttemptState.COMMITTING, T0, 1, T0, A, B);
    CompletionAttempt due = attempts.get("a-1");

    assertEquals("COMMITTING", saga.resend(due, Duration.ofSeconds(30), Duration.ofMillis(250)));
    assertEquals(null, saga.resend(due, Duration.ofSeconds(30), Duration.ofMillis(250)), "a second claim of the same due time loses");
    assertEquals(List.of("CommitRequested a-1"), commands.written);
    assertEquals(2, attempts.get("a-1").commandSends());
  }

  @Test
  void aReservingAttemptPastCancelAfterIsCancelledInsteadOfResent() {
    attempts.add("a-1", "tx-1", AttemptState.RESERVING, T0, 3, T0.plusSeconds(31), A, B);
    clock.advance(Duration.ofSeconds(31));

    saga.resend(attempts.get("a-1"), Duration.ofSeconds(30), Duration.ofMillis(250));

    assertEquals(AttemptState.CANCELLING, attempts.get("a-1").state());
    assertEquals(List.of("CancelRequested a-1"), commands.written);
  }

  @Test
  void aCommittingAttemptIsNeverCancelled() {
    attempts.add("a-1", "tx-1", AttemptState.COMMITTING, T0, 3, T0.plusSeconds(60), A, B);
    clock.advance(Duration.ofSeconds(60));

    saga.resend(attempts.get("a-1"), Duration.ofSeconds(30), Duration.ofMillis(250));

    assertEquals(AttemptState.COMMITTING, attempts.get("a-1").state());
    assertEquals(List.of("CommitRequested a-1"), commands.written);
  }

  private void attempt(String id, AttemptState state) {
    attempts.add(id, "tx-1", state, T0, 1, T0.plus(CompletionSaga.FIRST_RESEND_AFTER), A, B);
  }

  /** A reply whose reservation holds {@code reservedA} of SKU-A and {@code reservedB} of SKU-B. */
  private static InventoryReplyV1 reply(String attemptId, String type, ReservationStateV1 state, int reservedA, int reservedB) {
    ReservationV1 reservation = new ReservationV1(attemptId, "tx-1", state,
        List.of(new ReservationLineV1("SKU-A", 3, reservedA), new ReservationLineV1("SKU-B", 1, reservedB)), T0, null);
    return new InventoryReplyV1(1, "m-" + type, type, attemptId, "ReserveRequested", reservation, null);
  }

  private double ignored(String reason) {
    var counter = meters.find("checkout.saga.reply.ignored").tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }
}
