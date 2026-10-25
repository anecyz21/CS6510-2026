package edu.northeastern.cs6510.checkout.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.checkout.inventory.InventoryClient;
import edu.northeastern.cs6510.checkout.inventory.InventoryUnavailableException;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** The saga driver against in-memory fakes: state transitions, lost races, and no remote call inside a transaction (T042). */
class AttemptDriverTest {
  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(5);
  private static final String TX = "tx-1";

  private FlagTransactions transactions;
  private FakeAttempts attempts;
  private FakeInventory inventory;
  private AttemptDriver driver;

  @BeforeEach
  void setUp() {
    transactions = new FlagTransactions();
    attempts = new FakeAttempts(transactions);
    inventory = new FakeInventory(transactions);
    driver = new AttemptDriver(attempts, inventory, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void fullReservationIsCommittedAndFinalized() {
    attempts.add("a-1", line("SKU-A", 2), line("SKU-B", 1));
    inventory.grants = Map.of("SKU-A", 2, "SKU-B", 1);

    assertEquals(AttemptDriver.Outcome.FINALIZED, driver.drive("a-1", DEADLINE));
    assertEquals(AttemptState.FINALIZED, attempts.get("a-1").state());
    assertEquals(Map.of("SKU-A", 2, "SKU-B", 1), reserved("a-1"));
    assertEquals(List.of("reserve a-1", "commit a-1"), inventory.calls);
    assertTrue(attempts.finalized.contains("a-1"));
  }

  @Test
  void partialReservationStoresGrantedQuantitiesAsTheReceiptIntent() {
    attempts.add("a-1", line("SKU-A", 3), line("SKU-B", 1));
    inventory.grants = Map.of("SKU-A", 2, "SKU-B", 1);

    assertEquals(AttemptDriver.Outcome.FINALIZED, driver.drive("a-1", DEADLINE));
    assertEquals(Map.of("SKU-A", 2, "SKU-B", 1), reserved("a-1"));
  }

  @Test
  void allZeroReservationIsRejectedAndUnfreezesTheBasket() {
    attempts.add("a-1", line("SKU-A", 3));
    inventory.grants = Map.of("SKU-A", 0);

    assertEquals(AttemptDriver.Outcome.REJECTED, driver.drive("a-1", DEADLINE));
    assertEquals(AttemptState.REJECTED, attempts.get("a-1").state());
    assertTrue(attempts.cleared.contains("a-1"));
    assertEquals(List.of("reserve a-1"), inventory.calls);
  }

  @Test
  void cancelledTombstoneEndsTheAttemptWithoutCommitting() {
    attempts.add("a-1", line("SKU-A", 1));
    inventory.tombstone = true;

    assertEquals(AttemptDriver.Outcome.CANCELLED, driver.drive("a-1", DEADLINE));
    assertTrue(attempts.cleared.contains("a-1"));
    assertEquals(List.of("reserve a-1"), inventory.calls);
  }

  @Test
  void lostTransitionRaceReReadsAndContinuesFromTheWinnersState() {
    attempts.add("a-1", line("SKU-A", 2));
    inventory.grants = Map.of("SKU-A", 2);
    attempts.loseNextTransition = true;   // another driver prepares the attempt first

    assertEquals(AttemptDriver.Outcome.FINALIZED, driver.drive("a-1", DEADLINE));
    assertEquals(List.of("reserve a-1", "commit a-1"), inventory.calls);
    assertEquals(AttemptState.FINALIZED, attempts.get("a-1").state());
  }

  @Test
  void unknownInventoryOutcomeLeavesTheAttemptRecoverable() {
    attempts.add("a-1", line("SKU-A", 1));
    inventory.unavailable = true;

    assertEquals(AttemptDriver.Outcome.UNRESOLVED, driver.drive("a-1", DEADLINE));
    CompletionAttempt attempt = attempts.get("a-1");
    assertEquals(AttemptState.RESERVING, attempt.state());
    assertEquals(1, attempt.stepFailures());
    assertFalse(attempts.cleared.contains("a-1"), "an unconfirmed attempt keeps the basket frozen");
  }

  @Test
  void mismatchedReservationLinesNeedAttention() {
    attempts.add("a-1", line("SKU-A", 1));
    inventory.grants = Map.of("SKU-OTHER", 1);

    assertEquals(AttemptDriver.Outcome.UNRESOLVED, driver.drive("a-1", DEADLINE));
    assertTrue(attempts.get("a-1").needsAttention());
    assertEquals(AttemptState.RESERVING, attempts.get("a-1").state());
  }

  @Test
  void neverCallsInventoryInsideALocalTransaction() {
    attempts.add("a-1", line("SKU-A", 2), line("SKU-B", 1));
    inventory.grants = Map.of("SKU-A", 1, "SKU-B", 1);
    driver.drive("a-1", DEADLINE);
    assertFalse(inventory.calledInsideTransaction);
    assertEquals(2, inventory.calls.size());
  }

  private Map<String, Integer> reserved(String attemptId) {
    Map<String, Integer> out = new HashMap<>();
    for (AttemptLine l : attempts.get(attemptId).lines()) out.put(l.sku(), l.reservedQuantity());
    return out;
  }

  private static AttemptLine line(String sku, int quantity) { return new AttemptLine(sku, quantity, 100, "Item " + sku, null); }

  /** Marks the time spent inside a "local transaction" so the inventory fake can detect remote calls made there. */
  static final class FlagTransactions implements TransactionOperations {
    boolean inside;

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
      inside = true;
      try {
        return action.doInTransaction(new SimpleTransactionStatus());
      } finally {
        inside = false;
      }
    }
  }

  static final class FakeInventory implements InventoryClient {
    private final FlagTransactions transactions;
    final List<String> calls = new ArrayList<>();
    Map<String, Integer> grants = Map.of();
    boolean tombstone;
    boolean unavailable;
    boolean calledInsideTransaction;

    FakeInventory(FlagTransactions transactions) { this.transactions = transactions; }

    @Override
    public Result reserve(String attemptId, ReserveRequestV1 request) {
      record("reserve " + attemptId);
      if (tombstone) return new Ok(new ReservationV1(attemptId, request.transactionId(), ReservationStateV1.CANCELLED, List.of(), NOW, NOW));
      List<ReservationLineV1> lines = new ArrayList<>();
      int total = 0;
      if (grants.keySet().equals(skus(request))) {
        for (var l : request.lines()) {
          int g = grants.get(l.sku());
          total += g;
          lines.add(new ReservationLineV1(l.sku(), l.quantity(), g));
        }
      } else {
        for (var e : grants.entrySet()) {
          lines.add(new ReservationLineV1(e.getKey(), e.getValue(), e.getValue()));
          total += e.getValue();
        }
      }
      ReservationStateV1 state = total > 0 ? ReservationStateV1.RESERVED : ReservationStateV1.RELEASED;
      return new Ok(new ReservationV1(attemptId, request.transactionId(), state, lines, NOW, null));
    }

    @Override
    public Result commit(String attemptId) {
      record("commit " + attemptId);
      return new Ok(new ReservationV1(attemptId, TX, ReservationStateV1.COMMITTED, List.of(), NOW, NOW));
    }

    @Override
    public Result cancel(String attemptId) { record("cancel " + attemptId); return new NotFound(); }

    @Override
    public Result get(String attemptId) { record("get " + attemptId); return new NotFound(); }

    private void record(String call) {
      if (transactions.inside) calledInsideTransaction = true;
      calls.add(call);
      if (unavailable) throw new InventoryUnavailableException("connect timed out");
    }

    private static Set<String> skus(ReserveRequestV1 request) {
      Set<String> s = new HashSet<>();
      request.lines().forEach(l -> s.add(l.sku()));
      return s;
    }
  }

  static final class FakeAttempts implements CompletionAttemptRepository {
    private final FlagTransactions transactions;
    private final Map<String, CompletionAttempt> store = new HashMap<>();
    final Set<String> cleared = new HashSet<>();
    final Set<String> finalized = new HashSet<>();
    boolean loseNextTransition;

    FakeAttempts(FlagTransactions transactions) { this.transactions = transactions; }

    void add(String attemptId, AttemptLine... lines) {
      store.put(attemptId, new CompletionAttempt(attemptId, TX, AttemptState.RESERVING, NOW, NOW, null, 0, NOW, false, null, List.of(lines)));
    }

    CompletionAttempt get(String attemptId) { return store.get(attemptId); }

    @Override
    public Optional<CompletionAttempt> find(String attemptId) { return Optional.ofNullable(store.get(attemptId)); }

    @Override
    public boolean transition(String attemptId, AttemptState from, AttemptState to, Instant now) {
      requireTransaction();
      CompletionAttempt a = store.get(attemptId);
      if (loseNextTransition) {
        // Simulate a concurrent driver that already prepared the same attempt.
        loseNextTransition = false;
        List<AttemptLine> prepared = a.lines().stream()
            .map(l -> new AttemptLine(l.sku(), l.requestedQuantity(), l.unitPriceCents(), l.name(), l.requestedQuantity())).toList();
        store.put(attemptId, with(a, AttemptState.COMMITTING, prepared, a.stepFailures(), a.needsAttention()));
        return false;
      }
      if (a.state() != from) return false;
      store.put(attemptId, with(a, to, a.lines(), 0, a.needsAttention()));
      return true;
    }

    @Override
    public void storeReserved(String attemptId, Map<String, Integer> reservedBySku) {
      requireTransaction();
      CompletionAttempt a = store.get(attemptId);
      List<AttemptLine> lines = a.lines().stream()
          .map(l -> new AttemptLine(l.sku(), l.requestedQuantity(), l.unitPriceCents(), l.name(), reservedBySku.get(l.sku()))).toList();
      store.put(attemptId, with(a, a.state(), lines, a.stepFailures(), a.needsAttention()));
    }

    @Override
    public void clearActiveAttempt(String transactionId, String attemptId) { requireTransaction(); cleared.add(attemptId); }

    @Override
    public boolean finalizeAttempt(CompletionAttempt attempt, Instant now) {
      requireTransaction();
      CompletionAttempt a = store.get(attempt.attemptId());
      if (a.state() != AttemptState.COMMITTING) return false;
      store.put(a.attemptId(), with(a, AttemptState.FINALIZED, a.lines(), 0, a.needsAttention()));
      finalized.add(a.attemptId());
      cleared.add(a.attemptId());
      return true;
    }

    @Override
    public void recordStepFailure(String attemptId, String error, Instant nextRetryAt, Instant now) {
      requireTransaction();
      CompletionAttempt a = store.get(attemptId);
      store.put(attemptId, with(a, a.state(), a.lines(), a.stepFailures() + 1, a.needsAttention()));
    }

    @Override
    public void markNeedsAttention(String attemptId, String error, Instant now) {
      requireTransaction();
      CompletionAttempt a = store.get(attemptId);
      store.put(attemptId, with(a, a.state(), a.lines(), a.stepFailures(), true));
    }

    @Override public Optional<TransactionLock> lockTransaction(String transactionId) { throw new UnsupportedOperationException(); }
    @Override public List<AttemptLine> basketLines(String transactionId) { throw new UnsupportedOperationException(); }
    @Override public void insertAttemptWithLines(String attemptId, String transactionId, List<AttemptLine> lines, Instant now) { throw new UnsupportedOperationException(); }
    @Override public Optional<CompletionAttempt> findActiveForTransaction(String transactionId) { throw new UnsupportedOperationException(); }

    /** Writes must happen inside a local transaction; the driver must open one for each. */
    private void requireTransaction() {
      if (!transactions.inside) throw new AssertionError("repository write outside a local transaction");
    }

    private static CompletionAttempt with(CompletionAttempt a, AttemptState state, List<AttemptLine> lines, int failures, boolean attention) {
      return new CompletionAttempt(a.attemptId(), a.transactionId(), state, a.createdAt(), NOW, a.finalizedAt(), failures,
          a.nextRetryAt(), attention, a.lastError(), lines);
    }
  }
}
