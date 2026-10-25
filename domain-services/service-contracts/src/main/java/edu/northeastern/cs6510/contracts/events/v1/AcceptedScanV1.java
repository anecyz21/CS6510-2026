package edu.northeastern.cs6510.contracts.events.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Value of a {@code checkout.accepted-scans.v1} record. {@code eventId} is the deduplication key;
 * {@code acceptedAt} and {@code outboxId} are informational and never ordering keys.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcceptedScanV1(
    int schemaVersion,
    String eventId,
    String eventType,
    String transactionId,
    String stationId,
    String sku,
    Instant acceptedAt,
    long outboxId) {
  public static final String TOPIC = "checkout.accepted-scans.v1";
  public static final String EVENT_TYPE = "AcceptedScan";
  public static final int SCHEMA_VERSION = 1;
}
