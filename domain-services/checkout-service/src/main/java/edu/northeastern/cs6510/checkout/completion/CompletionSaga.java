package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import edu.northeastern.cs6510.checkout.fault.FaultInjector.FaultPoint;
import edu.northeastern.cs6510.checkout.messaging.CommandOutbox;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The completion saga driven by Inventory's replies (005 research R5, data-model state machine).
 * Each step is one local transaction: a conditional update from the expected state, its side
 * writes, and the next command written to {@code command_outbox}. A reply that does not match the
 * attempt's current state is a repeat or arrived late, and changes nothing. No step waits for
 * Inventory; the request that froze the basket waits on {@link AttemptWaiters} instead.
 *
 * <p>From RESERVING the attempt may still be cancelled (by recovery, after {@code cancel-after}).
 * Once Inventory's grant is stored and the attempt is COMMITTING, it only moves forward.
 */
@Component
public class CompletionSaga {
  private static final Logger log = LoggerFactory.getLogger(CompletionSaga.class);
  /** A healthy saga gets its reply well within this; only then does recovery resend the command. */
  public static final Duration FIRST_RESEND_AFTER = Duration.ofSeconds(1);

  private final CompletionAttemptRepository attempts;
  private final CommandOutbox commands;
  private final AttemptWaiters waiters;
  private final TransactionOperations transactions;
  private final FaultInjector faults;
  private final MeterRegistry meters;
  private final Clock clock;

  @Autowired
  public CompletionSaga(CompletionAttemptRepository attempts, CommandOutbox commands, AttemptWaiters waiters,
                        PlatformTransactionManager transactionManager, FaultInjector faults, MeterRegistry meters) {
    this(attempts, commands, waiters, new TransactionTemplate(transactionManager), faults, meters, Clock.systemUTC());
  }

  /** For tests: explicit transactions and clock. */
  public CompletionSaga(CompletionAttemptRepository attempts, CommandOutbox commands, AttemptWaiters waiters,
                        TransactionOperations transactions, FaultInjector faults, MeterRegistry meters, Clock clock) {
    this.attempts = attempts;
    this.commands = commands;
    this.waiters = waiters;
    this.transactions = transactions;
    this.faults = faults;
    this.meters = meters;
    this.clock = clock;
  }

  public Instant firstResendAt(Instant now) { return now.plus(FIRST_RESEND_AFTER); }

  /** Writes the attempt's ReserveRequested; must run inside the transaction that froze the basket. */
  public void start(String attemptId, String transactionId, List<AttemptLine> lines, Instant now) {
    commands.reserve(attemptId, transactionId, reserveLines(lines), now);
  }

  /** Applies one reply from {@code inventory.replies.v1}. Safe to call again with the same reply. */
  public void onReply(InventoryReplyV1 reply) {
    CompletionAttempt attempt = attempts.find(reply.attemptId()).orElse(null);
    if (attempt == null) {
      ignored("unknown-attempt", reply);
      return;
    }
    if (attempt.state().isTerminal()) {
      ignored("settled", reply);
      return;
    }
    if (InventoryReplyV1.CONFLICT.equals(reply.messageType())) {
      park(attempt, "inventory replied " + (reply.error() == null ? "Conflict" : reply.error().code() + ": " + reply.error().message())
          + " to " + reply.command());
      return;
    }
    switch (attempt.state()) {
      case RESERVING -> onReplyWhileReserving(attempt, reply);
      case COMMITTING -> {
        if (InventoryReplyV1.COMMITTED.equals(reply.messageType())) finalizeAttempt(attempt);
        else ignored("stale", reply);
      }
      case CANCELLING -> {
        // Released answers the cancel, or the reserve found nothing available; either way nothing is held.
        if (InventoryReplyV1.RELEASED.equals(reply.messageType()) || InventoryReplyV1.CANCELLED.equals(reply.messageType())) {
          settle(attempt, AttemptState.CANCELLING, AttemptState.CANCELLED, null);
        } else {
          ignored("stale", reply);
        }
      }
      default -> ignored("stale", reply);
    }
  }

  private void onReplyWhileReserving(CompletionAttempt attempt, InventoryReplyV1 reply) {
    switch (reply.messageType()) {
      case InventoryReplyV1.RESERVED -> {
        Map<String, Integer> granted = grants(attempt, reply.reservation());
        if (granted == null) {
          park(attempt, "reservation lines do not match the attempt snapshot");
          return;
        }
        Instant now = now();
        boolean won = Boolean.TRUE.equals(transactions.execute(s -> {
          // Durable receipt intent: from here on the attempt only moves forward.
          if (!attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.COMMITTING, now, firstResendAt(now))) return false;
          attempts.storeReserved(attempt.attemptId(), granted);
          commands.commit(attempt.attemptId(), now);
          return true;
        }));
        if (won) faults.checkpoint(FaultPoint.AFTER_RESERVED_APPLIED);
        else ignored("lost-race", reply);
      }
      case InventoryReplyV1.RELEASED -> {
        // Nothing was available: the basket stays open and unchanged.
        Map<String, Integer> none = new HashMap<>();
        for (AttemptLine line : attempt.lines()) none.put(line.sku(), 0);
        settle(attempt, AttemptState.RESERVING, AttemptState.REJECTED, none);
      }
      // A cancel tombstone won the race against the reserve: this reservation never holds stock.
      case InventoryReplyV1.CANCELLED -> settle(attempt, AttemptState.RESERVING, AttemptState.CANCELLED, null);
      default -> ignored("stale", reply);
    }
  }

  /** COMMITTING to FINALIZED with the receipt, in one transaction (004 finalizeAttempt). */
  private void finalizeAttempt(CompletionAttempt attempt) {
    // Stored in TIMESTAMP(6): truncated so the receipt built from memory equals the stored one.
    Instant now = now().truncatedTo(ChronoUnit.MICROS);
    CompletionAttempt finalized = new CompletionAttempt(attempt.attemptId(), attempt.transactionId(), AttemptState.FINALIZED,
        attempt.createdAt(), now, now, attempt.commandSends(), attempt.nextRetryAt(), false, attempt.lastError(), attempt.lines());
    boolean won = Boolean.TRUE.equals(transactions.execute(s -> {
      if (!attempts.finalizeAttempt(attempt, now)) return false;
      afterCommit(() -> waiters.settled(finalized));
      return true;
    }));
    if (!won) {
      ignored("lost-race", null);
      return;
    }
    roundtrip(attempt, AttemptState.FINALIZED, now);
    faults.checkpoint(FaultPoint.AFTER_COMMITTED_APPLIED);
  }

  /** A terminal transition that sends nothing, clears the freeze and wakes the waiting request. */
  private void settle(CompletionAttempt attempt, AttemptState from, AttemptState to, Map<String, Integer> grants) {
    Instant now = now();
    CompletionAttempt settled = new CompletionAttempt(attempt.attemptId(), attempt.transactionId(), to, attempt.createdAt(), now,
        null, attempt.commandSends(), attempt.nextRetryAt(), false, attempt.lastError(), attempt.lines());
    boolean won = Boolean.TRUE.equals(transactions.execute(s -> {
      if (!attempts.transition(attempt.attemptId(), from, to, now, now)) return false;
      if (grants != null) attempts.storeReserved(attempt.attemptId(), grants);
      attempts.clearActiveAttempt(attempt.transactionId(), attempt.attemptId());
      afterCommit(() -> waiters.settled(settled));
      return true;
    }));
    if (won) roundtrip(attempt, to, now);
    else ignored("lost-race", null);
  }

  /**
   * Recovery: writes the current state's command again if this caller wins the resend claim. A
   * RESERVING attempt older than {@code cancelAfter} is moved to CANCELLING and sent a cancel instead.
   * Returns what was sent, or null when the attempt moved on or another sweep claimed it.
   */
  public String resend(CompletionAttempt attempt, Duration cancelAfter, Duration backoff) {
    Instant now = now();
    if (attempt.state() == AttemptState.RESERVING && attempt.createdAt().isBefore(now.minus(cancelAfter))) {
      boolean cancelled = Boolean.TRUE.equals(transactions.execute(s -> {
        if (!attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.CANCELLING, now, firstResendAt(now))) return false;
        commands.cancel(attempt.attemptId(), now);
        return true;
      }));
      return cancelled ? "CancelRequested (cancel-after)" : null;
    }
    return transactions.execute(s -> {
      if (!attempts.claimResend(attempt.attemptId(), attempt.state(), attempt.nextRetryAt(), now, now.plus(backoff))) return null;
      switch (attempt.state()) {
        case RESERVING -> commands.reserve(attempt.attemptId(), attempt.transactionId(), reserveLines(attempt.lines()), now);
        case COMMITTING -> commands.commit(attempt.attemptId(), now);
        case CANCELLING -> commands.cancel(attempt.attemptId(), now);
        default -> { return null; }
      }
      return attempt.state().name();
    });
  }

  /** An impossible reply: flag it for operators and stop resending until an operator retry. */
  public void park(CompletionAttempt attempt, String problem) {
    log.error("completion attempt {} needs attention: {}", attempt.attemptId(), problem);
    Instant now = now();
    transactions.executeWithoutResult(s -> attempts.parkForOperator(attempt.attemptId(), problem, now));
    meters.counter("checkout.saga.parked").increment();
  }

  /** Granted quantity per SKU, or null when Inventory's lines do not match the immutable snapshot. */
  static Map<String, Integer> grants(CompletionAttempt attempt, ReservationV1 reservation) {
    List<ReservationLineV1> lines = reservation == null ? null : reservation.lines();
    if (lines == null || lines.size() != attempt.lines().size()) return null;
    Map<String, Integer> requested = new HashMap<>();
    for (AttemptLine line : attempt.lines()) requested.put(line.sku(), line.requestedQuantity());
    Map<String, Integer> granted = new HashMap<>();
    for (ReservationLineV1 line : lines) {
      Integer asked = requested.get(line.sku());
      if (asked == null || asked != line.requestedQuantity() || line.reservedQuantity() < 0 || line.reservedQuantity() > asked) return null;
      granted.put(line.sku(), line.reservedQuantity());
    }
    return granted;
  }

  private static List<ReserveLineV1> reserveLines(List<AttemptLine> lines) {
    return lines.stream().map(l -> new ReserveLineV1(l.sku(), l.requestedQuantity())).toList();
  }

  private void ignored(String reason, InventoryReplyV1 reply) {
    meters.counter("checkout.saga.reply.ignored", "reason", reason).increment();
    if (reply != null) log.debug("ignored {} for attempt {} ({})", reply.messageType(), reply.attemptId(), reason);
  }

  private void roundtrip(CompletionAttempt attempt, AttemptState outcome, Instant at) {
    Timer.builder("checkout.saga.roundtrip").tag("outcome", outcome.name()).publishPercentiles(0.5, 0.95, 0.99)
        .register(meters).record(Duration.between(attempt.createdAt(), at));
  }

  private Instant now() { return clock.instant(); }

  private static void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() { action.run(); }
    });
  }
}
