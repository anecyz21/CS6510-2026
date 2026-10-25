package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.inventory.InventoryClient;
import edu.northeastern.cs6510.checkout.inventory.InventoryUnavailableException;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives one completion attempt through the saga (research R8): reserve in Inventory, durably
 * record the receipt intent, commit in Inventory, then finalize in Checkout. It re-reads the
 * attempt before every step, every local write is its own short transaction, and no remote call
 * is ever made while a local transaction is open (FR-018). Every Inventory command repeats safely
 * because it is keyed by the attempt id.
 */
@Component
public class AttemptDriver {
  private static final Logger log = LoggerFactory.getLogger(AttemptDriver.class);
  private static final Duration RETRY_DELAY = Duration.ofMillis(250);

  /** How a drive ended. UNRESOLVED means the attempt is still in progress and stays recoverable. */
  public enum Outcome { FINALIZED, REJECTED, CANCELLED, UNRESOLVED }

  private final CompletionAttemptRepository attempts;
  private final InventoryClient inventory;
  private final TransactionOperations transactions;
  private final Clock clock;

  @Autowired
  public AttemptDriver(CompletionAttemptRepository attempts, InventoryClient inventory, PlatformTransactionManager transactionManager) {
    this(attempts, inventory, new TransactionTemplate(transactionManager), Clock.systemUTC());
  }

  AttemptDriver(CompletionAttemptRepository attempts, InventoryClient inventory, TransactionOperations transactions, Clock clock) {
    this.attempts = attempts;
    this.inventory = inventory;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** Advances the attempt until it is terminal, needs attention, a step fails, or the deadline passes. */
  public Outcome drive(String attemptId, Instant deadline) {
    while (true) {
      CompletionAttempt attempt = attempts.find(attemptId).orElseThrow(() -> new IllegalStateException("unknown attempt " + attemptId));
      switch (attempt.state()) {
        case FINALIZED: return Outcome.FINALIZED;
        case REJECTED: return Outcome.REJECTED;
        case CANCELLED: return Outcome.CANCELLED;
        default: break;
      }
      if (attempt.needsAttention() || !clock.instant().isBefore(deadline)) return Outcome.UNRESOLVED;
      try {
        boolean progressed = switch (attempt.state()) {
          case RESERVING -> reserveStep(attempt);
          case COMMITTING -> commitStep(attempt);
          default -> false;   // CANCELLING is driven by recovery (US2)
        };
        if (!progressed) return Outcome.UNRESOLVED;
      } catch (InventoryUnavailableException unknownOutcome) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status ->
            attempts.recordStepFailure(attemptId, unknownOutcome.getMessage(), now.plus(RETRY_DELAY), now));
        return Outcome.UNRESOLVED;
      }
    }
  }

  /** RESERVING: ask Inventory for every requested unit, then record what was granted. */
  private boolean reserveStep(CompletionAttempt attempt) {
    ReserveRequestV1 request = new ReserveRequestV1(attempt.transactionId(),
        attempt.lines().stream().map(l -> new ReserveLineV1(l.sku(), l.requestedQuantity())).toList());
    InventoryClient.Result result = inventory.reserve(attempt.attemptId(), request);
    if (!(result instanceof InventoryClient.Ok ok)) {
      return attention(attempt, "reserve returned " + result);
    }
    ReservationV1 reservation = ok.reservation();
    Instant now = clock.instant();
    switch (reservation.state()) {
      case RESERVED -> {
        Map<String, Integer> granted = grants(attempt, reservation);
        if (granted == null) return attention(attempt, "reservation lines do not match the attempt snapshot");
        transactions.executeWithoutResult(status -> {
          // Durable receipt intent: from here on the attempt only moves forward.
          if (attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.COMMITTING, now)) {
            attempts.storeReserved(attempt.attemptId(), granted);
          }
        });
        return true;
      }
      case RELEASED -> {
        // Nothing was available: the basket stays open and unchanged.
        transactions.executeWithoutResult(status -> {
          if (attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.REJECTED, now)) {
            attempts.storeReserved(attempt.attemptId(), zeroGrants(attempt));
            attempts.clearActiveAttempt(attempt.transactionId(), attempt.attemptId());
          }
        });
        return true;
      }
      case CANCELLED -> {
        // A cancel tombstone won the race: the reservation will never hold stock.
        transactions.executeWithoutResult(status -> {
          if (attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.CANCELLED, now)) {
            attempts.clearActiveAttempt(attempt.transactionId(), attempt.attemptId());
          }
        });
        return true;
      }
      default -> {
        return attention(attempt, "reserve returned state " + reservation.state() + " for a RESERVING attempt");
      }
    }
  }

  /** COMMITTING: commit the reservation (idempotent), then finalize the receipt and basket. */
  private boolean commitStep(CompletionAttempt attempt) {
    InventoryClient.Result result = inventory.commit(attempt.attemptId());
    if (!(result instanceof InventoryClient.Ok ok) || ok.reservation().state() != ReservationStateV1.COMMITTED) {
      // Commit is only sent from COMMITTING, and cancel never targets a COMMITTING attempt.
      return attention(attempt, "commit returned " + result);
    }
    Instant now = clock.instant();
    transactions.executeWithoutResult(status -> attempts.finalizeAttempt(attempt, now));
    return true;
  }

  /** Granted quantity per SKU, or null when Inventory's lines do not match the immutable snapshot. */
  private static Map<String, Integer> grants(CompletionAttempt attempt, ReservationV1 reservation) {
    List<ReservationLineV1> lines = reservation.lines();
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

  private static Map<String, Integer> zeroGrants(CompletionAttempt attempt) {
    Map<String, Integer> zero = new HashMap<>();
    for (AttemptLine line : attempt.lines()) zero.put(line.sku(), 0);
    return zero;
  }

  private boolean attention(CompletionAttempt attempt, String problem) {
    log.error("completion attempt {} needs attention: {}", attempt.attemptId(), problem);
    Instant now = clock.instant();
    transactions.executeWithoutResult(status -> attempts.markNeedsAttention(attempt.attemptId(), problem, now));
    return false;
  }
}
