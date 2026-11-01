package edu.northeastern.cs6510.checkout.completion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checkout-owned completion-attempt persistence. Every state change is a conditional update on
 * the expected current state: when a reply and a resend (or two replicas) race, exactly one wins.
 * Callers run each method inside one short local transaction and never wait for a reply in one.
 */
public interface CompletionAttemptRepository {

  /** Locked public status of a transaction and its active attempt; empty when it does not exist. */
  Optional<TransactionLock> lockTransaction(String transactionId);

  /** Current basket lines of a transaction, read under the caller's transaction lock. */
  List<AttemptLine> basketLines(String transactionId);

  /**
   * Inserts a RESERVING attempt with its snapshot lines and marks it active on the transaction.
   * {@code resendAt} is when recovery resends the reserve command if no reply has arrived.
   */
  void insertAttemptWithLines(String attemptId, String transactionId, List<AttemptLine> lines, Instant now, Instant resendAt);

  Optional<CompletionAttempt> find(String attemptId);

  Optional<CompletionAttempt> findActiveForTransaction(String transactionId);

  /**
   * {@code UPDATE ... SET state = to WHERE attempt_id = ? AND state = from}, restarting the command
   * count for the new state's command; true when this caller won.
   */
  boolean transition(String attemptId, AttemptState from, AttemptState to, Instant now, Instant resendAt);

  /** Stores Inventory's grant per SKU on the attempt lines (the durable receipt intent). */
  void storeReserved(String attemptId, Map<String, Integer> reservedBySku);

  /** Clears the freeze flag only if this attempt still holds it. */
  void clearActiveAttempt(String transactionId, String attemptId);

  /**
   * In one local transaction: COMMITTING to FINALIZED, receipt and lines for the purchased units,
   * basket reduced, transaction COMPLETED when its basket is empty, freeze flag cleared.
   * Returns false when the attempt was no longer COMMITTING (a repeated reply).
   */
  boolean finalizeAttempt(CompletionAttempt attempt, Instant now);

  /**
   * Claims one resend of the current state's command: {@code command_sends + 1} and the next resend
   * time, only if the attempt is still in {@code state}, due at {@code dueAt} and not flagged.
   * True when this caller won the claim.
   */
  boolean claimResend(String attemptId, AttemptState state, Instant dueAt, Instant now, Instant nextResendAt);

  /** Flags the attempt for operators and stops automatic resends until an operator retry. */
  void parkForOperator(String attemptId, String error, Instant now);

  /** Unresolved, unflagged attempts whose resend is due, oldest due first. */
  List<CompletionAttempt> findDueForResend(Instant now, int limit);

  /** Attempt count per state, for metrics. */
  Map<AttemptState, Long> countByState();

  /** Operator view: unresolved, needs-attention or all attempts, newest first. */
  List<CompletionAttempt> list(OperatorFilter filter, int limit);

  /** Makes a non-terminal attempt due now and clears its attention flag; false when the attempt is terminal or unknown. */
  boolean requestRetry(String attemptId, Instant now);

  /** The public status, active attempt and receipt header fields of a transaction, as read under a row lock. */
  record TransactionLock(String status, String activeAttemptId, String stationId, Instant startedAt) { }

  enum OperatorFilter { UNRESOLVED, NEEDS_ATTENTION, ALL }
}
