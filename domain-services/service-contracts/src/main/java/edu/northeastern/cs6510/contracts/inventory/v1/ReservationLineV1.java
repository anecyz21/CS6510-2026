package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Requested and granted quantity for one SKU; {@code 0 <= reservedQuantity <= requestedQuantity}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationLineV1(String sku, int requestedQuantity, int reservedQuantity) { }
