package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Value of an {@code inventory.commands.v1} record (005 contracts/inventory-reservation-messages.md).
 * The record key and the idempotency key is {@code attemptId}; a resend repeats the body under a
 * new {@code messageId}. {@code transactionId} and {@code lines} are set only on
 * {@code ReserveRequested}, where {@code lines} is the attempt's frozen basket snapshot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryCommandV1(
    int schemaVersion,
    String messageId,
    String messageType,
    String attemptId,
    String transactionId,
    List<ReserveLineV1> lines) {
  public static final String TOPIC = "inventory.commands.v1";
  public static final String RESERVE_REQUESTED = "ReserveRequested";
  public static final String COMMIT_REQUESTED = "CommitRequested";
  public static final String CANCEL_REQUESTED = "CancelRequested";
  public static final int SCHEMA_VERSION = 1;

  public static InventoryCommandV1 reserve(String messageId, String attemptId, String transactionId, List<ReserveLineV1> lines) {
    return new InventoryCommandV1(SCHEMA_VERSION, messageId, RESERVE_REQUESTED, attemptId, transactionId, lines);
  }

  public static InventoryCommandV1 commit(String messageId, String attemptId) {
    return new InventoryCommandV1(SCHEMA_VERSION, messageId, COMMIT_REQUESTED, attemptId, null, null);
  }

  public static InventoryCommandV1 cancel(String messageId, String attemptId) {
    return new InventoryCommandV1(SCHEMA_VERSION, messageId, CANCEL_REQUESTED, attemptId, null, null);
  }

  public static boolean isKnownType(String messageType) {
    return RESERVE_REQUESTED.equals(messageType) || COMMIT_REQUESTED.equals(messageType) || CANCEL_REQUESTED.equals(messageType);
  }

  /** The reserve request carried by a {@code ReserveRequested} command. */
  public ReserveRequestV1 reserveRequest() { return new ReserveRequestV1(transactionId, lines); }
}
