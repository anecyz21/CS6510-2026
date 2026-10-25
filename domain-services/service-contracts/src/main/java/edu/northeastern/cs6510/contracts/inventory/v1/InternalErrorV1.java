package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Internal error body; 409 responses carry the reservation that caused the conflict. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InternalErrorV1(String error, String message, ReservationV1 reservation) { }
