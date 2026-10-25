package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Body of {@code PUT /internal/v1/reservations/{attemptId}}; the path's attemptId is the idempotency key. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveRequestV1(String transactionId, List<ReserveLineV1> lines) { }
