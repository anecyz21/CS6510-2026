package edu.northeastern.cs6510.checkout.completion;

import edu.northeastern.cs6510.checkout.messaging.CommandOutbox;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Records the commands the saga writes, and fails if one is written outside a local transaction. */
public class RecordingCommands extends CommandOutbox {
  private final BooleanSupplier insideTransaction;
  /** "ReserveRequested a-1", "CommitRequested a-1", ... in write order. */
  public final List<String> written = new ArrayList<>();

  public RecordingCommands(BooleanSupplier insideTransaction) {
    super(null, null, null);
    this.insideTransaction = insideTransaction;
  }

  @Override
  public void reserve(String attemptId, String transactionId, List<ReserveLineV1> lines, Instant now) { record("ReserveRequested", attemptId); }

  @Override
  public void commit(String attemptId, Instant now) { record("CommitRequested", attemptId); }

  @Override
  public void cancel(String attemptId, Instant now) { record("CancelRequested", attemptId); }

  private void record(String type, String attemptId) {
    if (!insideTransaction.getAsBoolean()) throw new AssertionError(type + " written outside the transition's transaction");
    written.add(type + " " + attemptId);
  }
}
