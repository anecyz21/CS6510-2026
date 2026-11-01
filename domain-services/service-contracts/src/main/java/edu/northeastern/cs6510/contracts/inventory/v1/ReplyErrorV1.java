package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The error on a {@code Conflict} reply. {@code code} is one of {@code INVALID_RESERVATION_REQUEST},
 * {@code RESERVATION_MISMATCH}, {@code RESERVATION_NOT_FOUND}, {@code RESERVATION_NOT_COMMITTABLE}
 * or {@code RESERVATION_COMMITTED}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplyErrorV1(String code, String message) { }
