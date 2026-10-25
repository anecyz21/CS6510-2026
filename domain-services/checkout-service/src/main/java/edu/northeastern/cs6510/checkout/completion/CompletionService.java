package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.api.ApiException;
import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import edu.northeastern.cs6510.checkout.fault.FaultInjector.FaultPoint;
import edu.northeastern.cs6510.checkout.recovery.RecoveryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public completion (amendments A1–A5). Freezes the basket under a new attempt in one local
 * transaction, or joins the attempt that already owns it, then drives the saga outside any
 * transaction within the request deadline. Precedence: TRANSACTION_NOT_FOUND,
 * TRANSACTION_NOT_OPEN, join an active attempt, EMPTY_BASKET, new attempt.
 */
@Service
public class CompletionService {
  private final CompletionAttemptRepository attempts;
  private final AttemptDriver driver;
  private final ReceiptAssembler receipts;
  private final TransactionTemplate transactions;
  private final MeterRegistry meters;
  private final FaultInjector faults;
  private final CompletionProperties completion;
  private final RecoveryProperties recovery;

  public CompletionService(CompletionAttemptRepository attempts, AttemptDriver driver, ReceiptAssembler receipts,
                           PlatformTransactionManager transactionManager, MeterRegistry meters, FaultInjector faults,
                           CompletionProperties completion, RecoveryProperties recovery) {
    this.attempts = attempts;
    this.driver = driver;
    this.receipts = receipts;
    this.transactions = new TransactionTemplate(transactionManager);
    this.meters = meters;
    this.faults = faults;
    this.completion = completion;
    this.recovery = recovery;
  }

  public Map<String, Object> complete(String transactionId) {
    Instant deadline = Instant.now().plus(completion.requestDeadline());
    String owner = "request-" + UUID.randomUUID();
    Freeze freeze = transactions.execute(status -> freeze(transactionId, owner));
    if (freeze.joined()) {
      outcome("joined");
    } else {
      faults.checkpoint(FaultPoint.AFTER_FREEZE);
    }
    AttemptDriver.Outcome outcome = driver.drive(freeze.attemptId(), deadline, owner);
    return switch (outcome) {
      case FINALIZED -> {
        outcome("receipt");
        yield receipts.receipt(freeze.attemptId());
      }
      case REJECTED -> {
        outcome("insufficient");
        throw ApiException.insufficientStock(transactionId);
      }
      case CANCELLED, UNRESOLVED -> {
        // Not a cancellation: an unresolved attempt stays recoverable, and a later call sees its result (A4).
        outcome("unavailable");
        throw ApiException.serviceUnavailable("inventory");
      }
    };
  }

  /**
   * One local transaction: lock the transaction row and apply precedence. A completion arriving
   * while another attempt owns the basket joins that attempt instead of starting a second purchase.
   */
  private Freeze freeze(String transactionId, String owner) {
    CompletionAttemptRepository.TransactionLock tx = attempts.lockTransaction(transactionId)
        .orElseThrow(() -> ApiException.transactionNotFound(transactionId));
    if (!"OPEN".equals(tx.status())) throw ApiException.transactionNotOpen(transactionId);
    if (tx.activeAttemptId() != null) return new Freeze(tx.activeAttemptId(), true);
    List<AttemptLine> basket = attempts.basketLines(transactionId);
    if (basket.isEmpty()) throw ApiException.emptyBasket(transactionId);
    String attemptId = UuidV7.next();
    Instant now = Instant.now();
    attempts.insertAttemptWithLines(attemptId, transactionId, basket, now, owner, now.plus(recovery.leaseDuration()));
    return new Freeze(attemptId, false);
  }

  private record Freeze(String attemptId, boolean joined) { }

  private void outcome(String result) { meters.counter("checkout.completion.outcome", "result", result).increment(); }
}
