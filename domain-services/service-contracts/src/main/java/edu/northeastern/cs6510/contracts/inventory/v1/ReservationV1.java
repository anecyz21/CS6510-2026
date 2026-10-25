package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/** The reservation resource returned by every reservation command and query. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationV1(
    String attemptId,
    String transactionId,
    ReservationStateV1 state,
    List<ReservationLineV1> lines,
    Instant createdAt,
    Instant resolvedAt) { }
