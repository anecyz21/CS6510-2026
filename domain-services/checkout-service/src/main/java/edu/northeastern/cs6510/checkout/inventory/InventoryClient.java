package edu.northeastern.cs6510.checkout.inventory;

import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;

/**
 * Inventory internal API v1 reservation commands (contracts/inventory-internal-api.md). Every
 * command is keyed by the attempt id and safe to repeat. A transport failure, timeout or 5xx is an
 * unknown outcome and throws {@link InventoryUnavailableException}; implementations never retry,
 * so the saga driver decides when to repeat the same command.
 */
public interface InventoryClient {
  Result reserve(String attemptId, ReserveRequestV1 request);
  Result commit(String attemptId);
  Result cancel(String attemptId);
  Result get(String attemptId);

  /** A known outcome of one command. */
  sealed interface Result permits Ok, Conflict, NotFound { }

  /** 2xx with the reservation resource. */
  record Ok(ReservationV1 reservation) implements Result { }

  /** 409 with the conflict code (e.g. RESERVATION_NOT_COMMITTABLE) and the reservation it concerns. */
  record Conflict(String code, ReservationV1 reservation) implements Result { }

  /** 404 RESERVATION_NOT_FOUND. */
  record NotFound() implements Result { }
}
