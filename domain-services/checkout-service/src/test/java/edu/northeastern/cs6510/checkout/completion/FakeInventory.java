package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.inventory.InventoryClient;
import edu.northeastern.cs6510.checkout.inventory.InventoryUnavailableException;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Scripted Inventory for unit tests; records every call and whether one was made inside a local transaction. */
public class FakeInventory implements InventoryClient {
  private static final Instant AT = Instant.parse("2026-10-25T12:00:00Z");
  private final BooleanSupplier insideTransaction;
  public final List<String> calls = new ArrayList<>();
  public Map<String, Integer> grants = Map.of();
  public boolean tombstone;
  public boolean unavailable;
  public boolean cancelSeesCommitted;
  public boolean calledInsideTransaction;

  public FakeInventory(BooleanSupplier insideTransaction) { this.insideTransaction = insideTransaction; }

  @Override
  public Result reserve(String attemptId, ReserveRequestV1 request) {
    record("reserve " + attemptId);
    if (tombstone) return new Ok(reservation(attemptId, ReservationStateV1.CANCELLED, List.of()));
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
    return new Ok(reservation(attemptId, total > 0 ? ReservationStateV1.RESERVED : ReservationStateV1.RELEASED, lines));
  }

  @Override
  public Result commit(String attemptId) {
    record("commit " + attemptId);
    return new Ok(reservation(attemptId, ReservationStateV1.COMMITTED, List.of()));
  }

  @Override
  public Result cancel(String attemptId) {
    record("cancel " + attemptId);
    if (cancelSeesCommitted) return new Conflict("RESERVATION_COMMITTED", reservation(attemptId, ReservationStateV1.COMMITTED, List.of()));
    return new Ok(reservation(attemptId, ReservationStateV1.CANCELLED, List.of()));
  }

  @Override
  public Result get(String attemptId) { record("get " + attemptId); return new NotFound(); }

  public long count(String prefix) { return calls.stream().filter(c -> c.startsWith(prefix)).count(); }

  private void record(String call) {
    if (insideTransaction.getAsBoolean()) calledInsideTransaction = true;
    calls.add(call);
    if (unavailable) throw new InventoryUnavailableException("connect timed out");
  }

  private static ReservationV1 reservation(String attemptId, ReservationStateV1 state, List<ReservationLineV1> lines) {
    return new ReservationV1(attemptId, "tx-1", state, lines, AT, null);
  }

  private static Set<String> skus(ReserveRequestV1 request) {
    Set<String> s = new HashSet<>();
    request.lines().forEach(l -> s.add(l.sku()));
    return s;
  }
}
