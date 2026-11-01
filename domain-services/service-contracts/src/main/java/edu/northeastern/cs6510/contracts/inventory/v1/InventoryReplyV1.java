package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Value of an {@code inventory.replies.v1} record (005 contracts/inventory-reservation-messages.md).
 * The record key is {@code attemptId}; {@code command} names the command answered. On a
 * {@code Conflict} reply {@code error} is set and {@code reservation} is the stored reservation, or
 * null when there is none.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReplyV1(
    int schemaVersion,
    String messageId,
    String messageType,
    String attemptId,
    String command,
    ReservationV1 reservation,
    ReplyErrorV1 error) {
  public static final String TOPIC = "inventory.replies.v1";
  public static final String RESERVED = "Reserved";
  public static final String COMMITTED = "Committed";
  public static final String RELEASED = "Released";
  public static final String CANCELLED = "Cancelled";
  public static final String CONFLICT = "Conflict";
  public static final int SCHEMA_VERSION = 1;

  /** The reply type that reports a stored reservation state. */
  public static String typeFor(ReservationStateV1 state) {
    return switch (state) {
      case RESERVED -> RESERVED;
      case COMMITTED -> COMMITTED;
      case RELEASED -> RELEASED;
      case CANCELLED -> CANCELLED;
    };
  }
}
