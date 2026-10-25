package edu.northeastern.cs6510.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One requested SKU quantity in a reserve command. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveLineV1(String sku, int quantity) { }
