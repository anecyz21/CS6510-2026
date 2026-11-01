package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.api.ApiException;
import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import edu.northeastern.cs6510.checkout.fault.FaultInjector.FaultPoint;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public completion (004 amendments A1–A5) over the event-driven saga (005 research R5). One local
 * transaction freezes the basket under a new attempt and writes its ReserveRequested command, or
 * joins the attempt that already owns the basket. The request then waits, outside any transaction,
 * for Inventory's replies to settle the attempt, up to the request deadline. Precedence:
 * TRANSACTION_NOT_FOUND, TRANSACTION_NOT_OPEN, join an active attempt, EMPTY_BASKET, new attempt.
 */
@Service
public class CompletionService {
  private final CompletionAttemptRepository attempts;
  private final CompletionSaga saga;
  private final AttemptWaiters waiters;
  private final ReceiptAssembler receipts;
  private final TransactionTemplate transactions;
  private final MeterRegistry meters;
  private final FaultInjector faults;
  private final CompletionProperties completion;

  public CompletionService(CompletionAttemptRepository attempts, CompletionSaga saga, AttemptWaiters waiters, ReceiptAssembler receipts,
                           PlatformTransactionManager transactionManager, MeterRegistry meters, FaultInjector faults,
                           CompletionProperties completion) {
    this.attempts = attempts;
    this.saga = saga;
    this.waiters = waiters;
    this.receipts = receipts;
    this.transactions = new TransactionTemplate(transactionManager);
    this.meters = meters;
    this.faults = faults;
    this.completion = completion;
  }

  public Map<String, Object> complete(String transactionId) {
    Instant deadline = Instant.now().plus(completion.requestDeadline());
    Freeze freeze = transactions.execute(status -> freeze(transactionId));
    if (freeze.joined()) outcome("joined");
    else faults.checkpoint(FaultPoint.AFTER_FREEZE);
    AttemptWaiters.Result result = waiters.await(freeze.attemptId(), deadline);
    return switch (result.outcome()) {
      case FINALIZED -> {
        outcome("receipt");
        CompletionAttempt finalized = result.attempt();
        yield finalized != null && finalized.finalizedAt() != null
            ? receipts.receipt(finalized, freeze.stationId(), freeze.startedAt())
            : receipts.receipt(freeze.attemptId());
      }
      case REJECTED -> {
        outcome("insufficient");
        throw ApiException.insufficientStock(transactionId);
      }
      case CANCELLED, UNRESOLVED -> {
        // Not a cancellation: an unresolved attempt keeps settling, and a later call sees its result (A4).
        outcome("unavailable");
        throw ApiException.serviceUnavailable("inventory");
      }
    };
  }

  /**
   * One local transaction: lock the transaction row and apply precedence. A completion arriving
   * while another attempt owns the basket joins that attempt instead of starting a second purchase.
   * A new attempt's reserve command commits with it and is sent right after.
   */
  private Freeze freeze(String transactionId) {
    CompletionAttemptRepository.TransactionLock tx = attempts.lockTransaction(transactionId)
        .orElseThrow(() -> ApiException.transactionNotFound(transactionId));
    if (!"OPEN".equals(tx.status())) throw ApiException.transactionNotOpen(transactionId);
    if (tx.activeAttemptId() != null) return new Freeze(tx.activeAttemptId(), true, tx.stationId(), tx.startedAt());
    List<AttemptLine> basket = attempts.basketLines(transactionId);
    if (basket.isEmpty()) throw ApiException.emptyBasket(transactionId);
    String attemptId = UuidV7.next();
    Instant now = Instant.now();
    attempts.insertAttemptWithLines(attemptId, transactionId, basket, now, saga.firstResendAt(now));
    saga.start(attemptId, transactionId, basket, now);
    return new Freeze(attemptId, false, tx.stationId(), tx.startedAt());
  }

  /** The attempt that owns the basket and the receipt header fields read under the lock. */
  private record Freeze(String attemptId, boolean joined, String stationId, Instant startedAt) { }

  private void outcome(String result) { meters.counter("checkout.completion.outcome", "result", result).increment(); }
}
