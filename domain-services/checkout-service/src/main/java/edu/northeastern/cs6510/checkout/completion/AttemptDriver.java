package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import edu.northeastern.cs6510.checkout.fault.FaultInjector.FaultPoint;
import edu.northeastern.cs6510.checkout.inventory.InventoryClient;
import edu.northeastern.cs6510.checkout.inventory.InventoryUnavailableException;
import edu.northeastern.cs6510.checkout.recovery.RecoveryProperties;
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
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives one completion attempt through the saga (research R8): reserve in Inventory, durably
 * record the receipt intent, commit in Inventory, then finalize in Checkout; or, before the
 * intent is durable, cancel. One driver at a time holds the attempt's lease. It re-reads the
 * attempt before every step, every local write is its own short transaction, and no remote call
 * is made while a local transaction is open (FR-018). Inventory commands repeat safely because
 * they are keyed by the attempt id.
 */
@Component
public class AttemptDriver {
  private static final Logger log = LoggerFactory.getLogger(AttemptDriver.class);
  /** Longest one Inventory call can take (connect + read timeout of {@code HttpInventoryClient}). */
  static final Duration INVENTORY_CALL_BUDGET = Duration.ofMillis(1800);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

  /** How a drive ended. UNRESOLVED means the attempt is still in progress and stays recoverable. */
  public enum Outcome { FINALIZED, REJECTED, CANCELLED, UNRESOLVED }

  /** Sleeping is injectable so unit tests run without waiting. */
  public interface Sleeper { void sleep(Duration d) throws InterruptedException; }

  private final CompletionAttemptRepository attempts;
  private final InventoryClient inventory;
  private final TransactionOperations transactions;
  private final Clock clock;
  private final FaultInjector faults;
  private final RecoveryProperties recovery;
  private final Sleeper sleeper;

  @Autowired
  public AttemptDriver(CompletionAttemptRepository attempts, InventoryClient inventory, PlatformTransactionManager transactionManager,
                       FaultInjector faults, RecoveryProperties recovery) {
    this(attempts, inventory, new TransactionTemplate(transactionManager), Clock.systemUTC(), faults, recovery,
        d -> Thread.sleep(d.toMillis()));
  }

  /** For tests: explicit transactions, clock and sleeper. */
  public AttemptDriver(CompletionAttemptRepository attempts, InventoryClient inventory, TransactionOperations transactions, Clock clock,
                FaultInjector faults, RecoveryProperties recovery, Sleeper sleeper) {
    this.attempts = attempts;
    this.inventory = inventory;
    this.transactions = transactions;
    this.clock = clock;
    this.faults = faults;
    this.recovery = recovery;
    this.sleeper = sleeper;
  }

  /**
   * Drives the attempt as {@code owner} until it is terminal, a step fails, or the deadline passes.
   * When another owner holds the lease, waits for that driver's result instead (A5).
   */
  public Outcome drive(String attemptId, Instant deadline, String owner) {
    Instant now = clock.instant();
    Boolean leased = transactions.execute(s -> attempts.acquireLease(attemptId, owner, now.plus(recovery.leaseDuration()), now));
    if (!Boolean.TRUE.equals(leased)) return awaitOtherDriver(attemptId, deadline);
    try {
      return run(attemptId, deadline);
    } finally {
      transactions.executeWithoutResult(s -> attempts.releaseLease(attemptId, owner));
    }
  }

  private Outcome run(String attemptId, Instant deadline) {
    while (true) {
      CompletionAttempt attempt = find(attemptId);
      Outcome terminal = terminal(attempt.state());
      if (terminal != null) return terminal;
      if (!clock.instant().isBefore(deadline)) return Outcome.UNRESOLVED;
      try {
        boolean progressed = switch (attempt.state()) {
          case RESERVING -> reserveStep(attempt, deadline);
          case COMMITTING -> commitStep(attempt, deadline);
          case CANCELLING -> cancelStep(attempt, deadline);
          default -> false;
        };
        if (!progressed) return Outcome.UNRESOLVED;
      } catch (NoTimeLeft noTime) {
        // The deadline cannot fit another Inventory call; nothing failed, so nothing is recorded.
        return Outcome.UNRESOLVED;
      } catch (InventoryUnavailableException unknownOutcome) {
        // Not a cancellation: the attempt keeps its state and recovery repeats the same command.
        Instant failedAt = clock.instant();
        Instant retryAt = failedAt.plus(recovery.backoff(attempt.stepFailures()));
        transactions.executeWithoutResult(s -> attempts.recordStepFailure(attemptId, unknownOutcome.getMessage(), retryAt, failedAt));
        return Outcome.UNRESOLVED;
      }
    }
  }

  /** Another driver holds the lease: report its result once the attempt is terminal, or UNRESOLVED at the deadline. */
  private Outcome awaitOtherDriver(String attemptId, Instant deadline) {
    try {
      while (true) {
        Outcome terminal = terminal(find(attemptId).state());
        if (terminal != null) return terminal;
        if (!clock.instant().plus(POLL_INTERVAL).isBefore(deadline)) return Outcome.UNRESOLVED;
        pause(POLL_INTERVAL);
      }
    } catch (NoTimeLeft interrupted) {
      return Outcome.UNRESOLVED;
    }
  }

  /** RESERVING: ask Inventory for every requested unit, then record what was granted. */
  private boolean reserveStep(CompletionAttempt attempt, Instant deadline) {
    ReserveRequestV1 request = new ReserveRequestV1(attempt.transactionId(),
        attempt.lines().stream().map(l -> new ReserveLineV1(l.sku(), l.requestedQuantity())).toList());
    InventoryClient.Result result = call(() -> inventory.reserve(attempt.attemptId(), request), deadline);
    faults.checkpoint(FaultPoint.AFTER_RESERVE_RESPONSE);
    if (!(result instanceof InventoryClient.Ok ok)) return park(attempt, "reserve returned " + result);
    ReservationV1 reservation = ok.reservation();
    Instant now = clock.instant();
    switch (reservation.state()) {
      case RESERVED -> {
        Map<String, Integer> granted = grants(attempt, reservation);
        if (granted == null) return park(attempt, "reservation lines do not match the attempt snapshot");
        transactions.executeWithoutResult(s -> {
          // Durable receipt intent: from here on the attempt only moves forward.
          if (attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.COMMITTING, now)) {
            attempts.storeReserved(attempt.attemptId(), granted);
          }
        });
        faults.checkpoint(FaultPoint.AFTER_PREPARE);
        return true;
      }
      case RELEASED -> {
        // Nothing was available: the basket stays open and unchanged.
        transactions.executeWithoutResult(s -> {
          if (attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.REJECTED, now)) {
            attempts.storeReserved(attempt.attemptId(), zeroGrants(attempt));
            attempts.clearActiveAttempt(attempt.transactionId(), attempt.attemptId());
          }
        });
        return true;
      }
      case CANCELLED -> {
        // A cancel tombstone won the race: this reservation never holds stock.
        transactions.executeWithoutResult(s -> {
          if (attempts.transition(attempt.attemptId(), AttemptState.RESERVING, AttemptState.CANCELLED, now)) {
            attempts.clearActiveAttempt(attempt.transactionId(), attempt.attemptId());
          }
        });
        return true;
      }
      default -> {
        return park(attempt, "reserve returned state " + reservation.state() + " for a RESERVING attempt");
      }
    }
  }

  /** COMMITTING: commit the reservation (idempotent), then finalize the receipt and basket. Never cancelled. */
  private boolean commitStep(CompletionAttempt attempt, Instant deadline) {
    InventoryClient.Result result = call(() -> inventory.commit(attempt.attemptId()), deadline);
    faults.checkpoint(FaultPoint.AFTER_COMMIT_RESPONSE);
    if (!(result instanceof InventoryClient.Ok ok) || ok.reservation().state() != ReservationStateV1.COMMITTED) {
      // Commit is only sent from COMMITTING and cancel only from CANCELLING, so this cannot happen.
      return park(attempt, "commit returned " + result);
    }
    Instant now = clock.instant();
    transactions.executeWithoutResult(s -> attempts.finalizeAttempt(attempt, now));
    return true;
  }

  /** CANCELLING: release the reservation (or leave a tombstone); the basket stays open and unchanged. */
  private boolean cancelStep(CompletionAttempt attempt, Instant deadline) {
    InventoryClient.Result result = call(() -> inventory.cancel(attempt.attemptId()), deadline);
    if (result instanceof InventoryClient.Ok ok
        && (ok.reservation().state() == ReservationStateV1.RELEASED || ok.reservation().state() == ReservationStateV1.CANCELLED)) {
      Instant now = clock.instant();
      transactions.executeWithoutResult(s -> {
        if (attempts.transition(attempt.attemptId(), AttemptState.CANCELLING, AttemptState.CANCELLED, now)) {
          attempts.clearActiveAttempt(attempt.transactionId(), attempt.attemptId());
        }
      });
      return true;
    }
    if (result instanceof InventoryClient.Conflict conflict && "RESERVATION_COMMITTED".equals(conflict.code())) {
      return park(attempt, "cancel observed COMMITTED");
    }
    return park(attempt, "cancel returned " + result);
  }

  /**
   * Sends one Inventory command, repeating the same command on an unknown outcome up to the
   * in-request retry budget. A call is only started if it can finish before the deadline.
   */
  private InventoryClient.Result call(Supplier<InventoryClient.Result> command, Instant deadline) {
    for (int retry = 0; ; retry++) {
      if (clock.instant().plus(INVENTORY_CALL_BUDGET).isAfter(deadline)) throw new NoTimeLeft();
      try {
        return command.get();
      } catch (InventoryUnavailableException e) {
        Duration pause = recovery.retryPause(retry);
        if (retry >= recovery.inRequestRetries() || clock.instant().plus(pause).plus(INVENTORY_CALL_BUDGET).isAfter(deadline)) throw e;
        pause(pause);
      }
    }
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

  private static Outcome terminal(AttemptState state) {
    return switch (state) {
      case FINALIZED -> Outcome.FINALIZED;
      case REJECTED -> Outcome.REJECTED;
      case CANCELLED -> Outcome.CANCELLED;
      default -> null;
    };
  }

  /** An impossible state: flag it for operators and stop retrying it automatically. */
  private boolean park(CompletionAttempt attempt, String problem) {
    log.error("completion attempt {} needs attention: {}", attempt.attemptId(), problem);
    Instant now = clock.instant();
    transactions.executeWithoutResult(s -> attempts.parkForOperator(attempt.attemptId(), problem, now));
    return false;
  }

  private CompletionAttempt find(String attemptId) {
    return attempts.find(attemptId).orElseThrow(() -> new IllegalStateException("unknown attempt " + attemptId));
  }

  private void pause(Duration d) {
    try {
      sleeper.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new NoTimeLeft();
    }
  }

  /** The deadline leaves no room for another Inventory call. */
  private static final class NoTimeLeft extends RuntimeException {
    NoTimeLeft() { super(null, null, false, false); }
  }
}
