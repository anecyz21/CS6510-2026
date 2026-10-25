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
 * In-memory fake of {@link CompletionAttemptRepository} for driver and recovery unit tests. Writes
 * fail unless the test's transaction fake reports that a local transaction is open.
 */
public class InMemoryAttemptRepository implements CompletionAttemptRepository {
  private final BooleanSupplier insideTransaction;
  private final Clock clock;
  private final Map<String, CompletionAttempt> store = new HashMap<>();
  private final Map<String, String> leaseOwner = new HashMap<>();
  private final Map<String, Instant> leaseUntil = new HashMap<>();
  public final Set<String> cleared = new HashSet<>();
  public final Set<String> finalized = new HashSet<>();
  public final List<String> parked = new ArrayList<>();
  /** When set, the next transition loses to a concurrent driver that already prepared the attempt. */
  public boolean loseNextTransition;

  public InMemoryAttemptRepository(BooleanSupplier insideTransaction, Clock clock) {
    this.insideTransaction = insideTransaction;
    this.clock = clock;
  }

  public void add(String attemptId, String transactionId, AttemptState state, Instant createdAt, int stepFailures, AttemptLine... lines) {
    store.put(attemptId, new CompletionAttempt(attemptId, transactionId, state, createdAt, createdAt, null, stepFailures,
        createdAt, false, null, List.of(lines)));
  }

  public CompletionAttempt get(String attemptId) { return store.get(attemptId); }

  public void holdLease(String attemptId, String owner, Instant until) {
    leaseOwner.put(attemptId, owner);
    leaseUntil.put(attemptId, until);
  }

  @Override
  public Optional<CompletionAttempt> find(String attemptId) { return Optional.ofNullable(store.get(attemptId)); }

  @Override
  public boolean transition(String attemptId, AttemptState from, AttemptState to, Instant now) {
    write();
    CompletionAttempt a = store.get(attemptId);
    if (loseNextTransition) {
      loseNextTransition = false;
      List<AttemptLine> prepared = a.lines().stream()
          .map(l -> new AttemptLine(l.sku(), l.requestedQuantity(), l.unitPriceCents(), l.name(), l.requestedQuantity())).toList();
      store.put(attemptId, copy(a, AttemptState.COMMITTING, prepared, 0, a.needsAttention(), a.nextRetryAt()));
      return false;
    }
    if (a.state() != from) return false;
    store.put(attemptId, copy(a, to, a.lines(), 0, a.needsAttention(), a.nextRetryAt()));
    return true;
  }

  @Override
  public void storeReserved(String attemptId, Map<String, Integer> reservedBySku) {
    write();
    CompletionAttempt a = store.get(attemptId);
    List<AttemptLine> lines = a.lines().stream()
        .map(l -> new AttemptLine(l.sku(), l.requestedQuantity(), l.unitPriceCents(), l.name(), reservedBySku.get(l.sku()))).toList();
    store.put(attemptId, copy(a, a.state(), lines, a.stepFailures(), a.needsAttention(), a.nextRetryAt()));
  }

  @Override
  public void clearActiveAttempt(String transactionId, String attemptId) { write(); cleared.add(attemptId); }

  @Override
  public boolean finalizeAttempt(CompletionAttempt attempt, Instant now) {
    write();
    CompletionAttempt a = store.get(attempt.attemptId());
    if (a.state() != AttemptState.COMMITTING) return false;
    store.put(a.attemptId(), copy(a, AttemptState.FINALIZED, a.lines(), 0, a.needsAttention(), a.nextRetryAt()));
    finalized.add(a.attemptId());
    cleared.add(a.attemptId());
    return true;
  }

  @Override
  public void recordStepFailure(String attemptId, String error, Instant nextRetryAt, Instant now) {
    write();
    CompletionAttempt a = store.get(attemptId);
    store.put(attemptId, new CompletionAttempt(a.attemptId(), a.transactionId(), a.state(), a.createdAt(), now, a.finalizedAt(),
        a.stepFailures() + 1, nextRetryAt, a.needsAttention(), error, a.lines()));
  }

  @Override
  public void markNeedsAttention(String attemptId, String error, Instant now) {
    write();
    CompletionAttempt a = store.get(attemptId);
    store.put(attemptId, copy(a, a.state(), a.lines(), a.stepFailures(), true, a.nextRetryAt()));
  }

  @Override
  public void parkForOperator(String attemptId, String error, Instant now) {
    write();
    CompletionAttempt a = store.get(attemptId);
    store.put(attemptId, copy(a, a.state(), a.lines(), a.stepFailures(), true, now.plusSeconds(365L * 24 * 3600)));
    parked.add(attemptId + ": " + error);
  }

  @Override
  public boolean acquireLease(String attemptId, String owner, Instant until, Instant now) {
    write();
    Instant held = leaseUntil.get(attemptId);
    if (held != null && !held.isBefore(now) && !owner.equals(leaseOwner.get(attemptId))) return false;
    holdLease(attemptId, owner, until);
    return true;
  }

  @Override
  public void releaseLease(String attemptId, String owner) {
    write();
    if (owner.equals(leaseOwner.get(attemptId))) {
      leaseOwner.remove(attemptId);
      leaseUntil.remove(attemptId);
    }
  }

  @Override
  public void releaseLeasesOwnedBy(String owner) {
    leaseOwner.entrySet().removeIf(e -> {
      boolean mine = e.getValue().equals(owner);
      if (mine) leaseUntil.remove(e.getKey());
      return mine;
    });
  }

  @Override
  public List<String> findDueForRecovery(Instant now, int limit) {
    return store.values().stream()
        .filter(a -> !a.state().isTerminal() && !a.nextRetryAt().isAfter(now))
        .filter(a -> leaseUntil.get(a.attemptId()) == null || leaseUntil.get(a.attemptId()).isBefore(now))
        .sorted((x, y) -> x.nextRetryAt().compareTo(y.nextRetryAt()))
        .limit(limit).map(CompletionAttempt::attemptId).toList();
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
                                               String owner, Instant until) { throw new UnsupportedOperationException(); }
  @Override public Optional<CompletionAttempt> findActiveForTransaction(String transactionId) { throw new UnsupportedOperationException(); }

  private void write() {
    if (!insideTransaction.getAsBoolean()) throw new AssertionError("repository write outside a local transaction");
  }

  private CompletionAttempt copy(CompletionAttempt a, AttemptState state, List<AttemptLine> lines, int failures,
                                 boolean attention, Instant nextRetryAt) {
    return new CompletionAttempt(a.attemptId(), a.transactionId(), state, a.createdAt(), clock.instant(), a.finalizedAt(),
        failures, nextRetryAt, attention, a.lastError(), lines);
  }
}
