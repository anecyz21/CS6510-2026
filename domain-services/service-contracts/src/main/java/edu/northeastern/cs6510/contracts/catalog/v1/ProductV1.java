package edu.northeastern.cs6510.contracts.catalog.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code GET /internal/v1/products/{sku}}: one active product; prices are integer cents. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductV1(long catalogVersion, String sku, String name, long priceCents) { }
