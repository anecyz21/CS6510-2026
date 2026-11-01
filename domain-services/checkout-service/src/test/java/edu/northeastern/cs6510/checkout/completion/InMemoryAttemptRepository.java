package edu.northeastern.cs6510.checkout.completion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * In-memory fake of {@link CompletionAttemptRepository} for saga and recovery unit tests. Writes
 * fail unless the test's transaction fake reports that a local transaction is open.
 */
public class InMemoryAttemptRepository implements CompletionAttemptRepository {
  private final BooleanSupplier insideTransaction;
  private final Clock clock;
  private final Map<String, CompletionAttempt> store = new HashMap<>();
  public final Set<String> cleared = new HashSet<>();
  public final Set<String> finalized = new HashSet<>();
  public final List<String> parked = new ArrayList<>();

  public InMemoryAttemptRepository(BooleanSupplier insideTransaction, Clock clock) {
    this.insideTransaction = insideTransaction;
    this.clock = clock;
  }

  /** Stores an attempt in {@code state}, created at {@code createdAt}, with its resend due at {@code dueAt}. */
  public void add(String attemptId, String transactionId, AttemptState state, Instant createdAt, int commandSends, Instant dueAt,
                  AttemptLine... lines) {
    store.put(attemptId, new CompletionAttempt(attemptId, transactionId, state, createdAt, createdAt, null, commandSends,
        dueAt, false, null, List.of(lines)));
  }

  public CompletionAttempt get(String attemptId) { return store.get(attemptId); }

  @Override
  public Optional<CompletionAttempt> find(String attemptId) { return Optional.ofNullable(store.get(attemptId)); }

  @Override
  public boolean transition(String attemptId, AttemptState from, AttemptState to, Instant now, Instant resendAt) {
    write();
    CompletionAttempt a = store.get(attemptId);
    if (a == null || a.state() != from) return false;
    store.put(attemptId, copy(a, to, a.lines(), 1, a.needsAttention(), resendAt));
    return true;
  }

  @Override
  public void storeReserved(String attemptId, Map<String, Integer> reservedBySku) {
    write();
    CompletionAttempt a = store.get(attemptId);
    List<AttemptLine> lines = a.lines().stream()
        .map(l -> new AttemptLine(l.sku(), l.requestedQuantity(), l.unitPriceCents(), l.name(), reservedBySku.get(l.sku()))).toList();
    store.put(attemptId, copy(a, a.state(), lines, a.commandSends(), a.needsAttention(), a.nextRetryAt()));
  }

  @Override
  public void clearActiveAttempt(String transactionId, String attemptId) { write(); cleared.add(attemptId); }

  @Override
  public boolean finalizeAttempt(CompletionAttempt attempt, Instant now) {
    write();
    CompletionAttempt a = store.get(attempt.attemptId());
    if (a.state() != AttemptState.COMMITTING) return false;
    store.put(a.attemptId(), copy(a, AttemptState.FINALIZED, a.lines(), a.commandSends(), a.needsAttention(), a.nextRetryAt()));
    finalized.add(a.attemptId());
    cleared.add(a.attemptId());
    return true;
  }

  @Override
  public boolean claimResend(String attemptId, AttemptState state, Instant dueAt, Instant now, Instant nextResendAt) {
    write();
    CompletionAttempt a = store.get(attemptId);
    if (a == null || a.state() != state || a.needsAttention() || !a.nextRetryAt().equals(dueAt)) return false;
    store.put(attemptId, copy(a, a.state(), a.lines(), a.commandSends() + 1, false, nextResendAt));
    return true;
  }

  @Override
  public void parkForOperator(String attemptId, String error, Instant now) {
    write();
    CompletionAttempt a = store.get(attemptId);
    store.put(attemptId, copy(a, a.state(), a.lines(), a.commandSends(), true, now.plusSeconds(365L * 24 * 3600)));
    parked.add(attemptId + ": " + error);
  }

  @Override
  public List<CompletionAttempt> findDueForResend(Instant now, int limit) {
    return store.values().stream()
        .filter(a -> !a.state().isTerminal() && !a.needsAttention() && !a.nextRetryAt().isAfter(now))
        .sorted((x, y) -> x.nextRetryAt().compareTo(y.nextRetryAt()))
        .limit(limit).toList();
  }

  @Override
  public Map<AttemptState, Long> countByState() {
    Map<AttemptState, Long> counts = new EnumMap<>(AttemptState.class);
    for (AttemptState s : AttemptState.values()) counts.put(s, 0L);
    store.values().forEach(a -> counts.merge(a.state(), 1L, Long::sum));
    return counts;
  }

  @Override
  public List<CompletionAttempt> list(OperatorFilter filter, int limit) { return new ArrayList<>(store.values()); }

  @Override
  public boolean requestRetry(String attemptId, Instant now) { return false; }

  @Override public Optional<TransactionLock> lockTransaction(String transactionId) { throw new UnsupportedOperationException(); }
  @Override public List<AttemptLine> basketLines(String transactionId) { throw new UnsupportedOperationException(); }
  @Override public void insertAttemptWithLines(String attemptId, String transactionId, List<AttemptLine> lines, Instant now,
                                               Instant resendAt) { throw new UnsupportedOperationException(); }
  @Override public Optional<CompletionAttempt> findActiveForTransaction(String transactionId) { throw new UnsupportedOperationException(); }

  private void write() {
    if (!insideTransaction.getAsBoolean()) throw new AssertionError("repository write outside a local transaction");
  }

  private CompletionAttempt copy(CompletionAttempt a, AttemptState state, List<AttemptLine> lines, int sends,
                                 boolean attention, Instant nextRetryAt) {
    return new CompletionAttempt(a.attemptId(), a.transactionId(), state, a.createdAt(), clock.instant(), a.finalizedAt(),
        sends, nextRetryAt, attention, a.lastError(), lines);
  }
}
