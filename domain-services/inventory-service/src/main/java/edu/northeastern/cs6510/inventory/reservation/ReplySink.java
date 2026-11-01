package edu.northeastern.cs6510.inventory.reservation;

import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;

/**
 * Receives the stored outcome of a reservation command inside the command's local transaction, so
 * the reply written from it commits or rolls back with the change it reports (005 research R4).
 */
@FunctionalInterface
public interface ReplySink {
  ReplySink NONE = stored -> { };

  void reply(ReservationV1 stored);

  /** Calls {@link #reply} and returns {@code stored}, for use as a transaction's result. */
  default ReservationV1 replied(ReservationV1 stored) {
    reply(stored);
    return stored;
  }
}
