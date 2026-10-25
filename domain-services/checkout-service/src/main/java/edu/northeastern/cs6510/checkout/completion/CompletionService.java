package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.api.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public completion (amendments A1–A5). Freezes the basket under a new attempt in one local
 * transaction, then drives the saga outside any transaction. Precedence on completion:
 * TRANSACTION_NOT_FOUND, TRANSACTION_NOT_OPEN, active attempt, EMPTY_BASKET, new attempt.
 */
@Service
public class CompletionService {
  private final CompletionAttemptRepository attempts;
  private final AttemptDriver driver;
  private final ReceiptAssembler receipts;
  private final TransactionTemplate transactions;
  private final MeterRegistry meters;
  private final Duration requestDeadline;

  public CompletionService(CompletionAttemptRepository attempts, AttemptDriver driver, ReceiptAssembler receipts,
                           PlatformTransactionManager transactionManager, MeterRegistry meters,
                           @Value("${checkout.completion.request-deadline:5s}") Duration requestDeadline) {
    this.attempts = attempts;
    this.driver = driver;
    this.receipts = receipts;
    this.transactions = new TransactionTemplate(transactionManager);
    this.meters = meters;
    this.requestDeadline = requestDeadline;
  }

  public Map<String, Object> complete(String transactionId) {
    Instant deadline = Instant.now().plus(requestDeadline);
    String attemptId = transactions.execute(status -> freeze(transactionId));
    AttemptDriver.Outcome outcome = driver.drive(attemptId, deadline);
    return switch (outcome) {
      case FINALIZED -> {
        outcome("receipt");
        yield receipts.receipt(attemptId);
      }
      case REJECTED -> {
        outcome("insufficient");
        throw ApiException.insufficientStock(transactionId);
      }
      case CANCELLED, UNRESOLVED -> {
        // Not a cancellation: an unresolved attempt stays recoverable (A4).
        outcome("unavailable");
        throw ApiException.serviceUnavailable("inventory");
      }
    };
  }

  /** One local transaction: lock the transaction row, apply precedence, snapshot the basket, set the freeze flag. */
  private String freeze(String transactionId) {
    CompletionAttemptRepository.TransactionLock tx = attempts.lockTransaction(transactionId)
        .orElseThrow(() -> ApiException.transactionNotFound(transactionId));
    if (!"OPEN".equals(tx.status())) throw ApiException.transactionNotOpen(transactionId);
    if (tx.activeAttemptId() != null) {
      // Joining the active attempt arrives with recovery (US2, task T074).
      throw ApiException.serviceUnavailable("completion");
    }
    List<AttemptLine> basket = attempts.basketLines(transactionId);
    if (basket.isEmpty()) throw ApiException.emptyBasket(transactionId);
    String attemptId = UuidV7.next();
    attempts.insertAttemptWithLines(attemptId, transactionId, basket, Instant.now());
    return attemptId;
  }

  private void outcome(String result) { meters.counter("checkout.completion.outcome", "result", result).increment(); }
}
