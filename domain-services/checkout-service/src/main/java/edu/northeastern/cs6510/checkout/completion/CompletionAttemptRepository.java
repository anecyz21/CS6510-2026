package edu.northeastern.cs6510.checkout.completion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checkout-owned completion-attempt persistence. Every state change is a conditional update on
 * the expected current state: when two drivers race, exactly one wins and the other re-reads.
 * Callers run each method inside one short local transaction and never across a remote call.
 */
public interface CompletionAttemptRepository {

  /** Locked public status of a transaction and its active attempt; empty when it does not exist. */
  Optional<TransactionLock> lockTransaction(String transactionId);

  /** Current basket lines of a transaction, read under the caller's transaction lock. */
  List<AttemptLine> basketLines(String transactionId);

  /**
   * Inserts a RESERVING attempt with its snapshot lines, already leased to {@code leaseOwner}, and
   * marks it active on the transaction. Creating the attempt leased means recovery cannot pick it
   * up before the request that created it starts driving it.
   */
  void insertAttemptWithLines(String attemptId, String transactionId, List<AttemptLine> lines, Instant now,
                              String leaseOwner, Instant leaseUntil);

  Optional<CompletionAttempt> find(String attemptId);

  Optional<CompletionAttempt> findActiveForTransaction(String transactionId);

  /** {@code UPDATE ... SET state = to WHERE attempt_id = ? AND state = from}; true when this caller won. */
  boolean transition(String attemptId, AttemptState from, AttemptState to, Instant now);

  /** Stores Inventory's grant per SKU on the attempt lines (the durable receipt intent). */
  void storeReserved(String attemptId, Map<String, Integer> reservedBySku);

  /** Clears the freeze flag only if this attempt still holds it. */
  void clearActiveAttempt(String transactionId, String attemptId);

  /**
   * In one local transaction: COMMITTING to FINALIZED, receipt and lines for the purchased units,
   * basket reduced, transaction COMPLETED when its basket is empty, freeze flag cleared.
   * Returns false when another driver already moved the attempt on.
   */
  boolean finalizeAttempt(CompletionAttempt attempt, Instant now);

  /** Counts one more consecutive failed step and schedules the next recovery try. */
  void recordStepFailure(String attemptId, String error, Instant nextRetryAt, Instant now);

  /** Flags the attempt for operators; recovery keeps retrying it. */
  void markNeedsAttention(String attemptId, String error, Instant now);

  /** Flags an impossible state for operators and stops automatic retries until an operator retry. */
  void parkForOperator(String attemptId, String error, Instant now);

  /** Takes the single-driver lease if it is free, expired, or already this owner's. */
  boolean acquireLease(String attemptId, String owner, Instant leaseUntil, Instant now);

  void releaseLease(String attemptId, String owner);

  /** Releases every lease held by an owner (graceful shutdown). */
  void releaseLeasesOwnedBy(String owner);

  /** Unresolved attempts due for a retry whose lease is free or expired, oldest due first. */
  List<String> findDueForRecovery(Instant now, int limit);

  /** Attempt count per state, for metrics. */
  Map<AttemptState, Long> countByState();

  /** Operator view: unresolved, needs-attention or all attempts, newest first. */
  List<CompletionAttempt> list(OperatorFilter filter, int limit);

  /** Makes a non-terminal attempt due now and clears its attention flag; false when the attempt is terminal or unknown. */
  boolean requestRetry(String attemptId, Instant now);

  /** The public status and active attempt of a transaction, as read under a row lock. */
  record TransactionLock(String status, String activeAttemptId) { }

  enum OperatorFilter { UNRESOLVED, NEEDS_ATTENTION, ALL }
}
