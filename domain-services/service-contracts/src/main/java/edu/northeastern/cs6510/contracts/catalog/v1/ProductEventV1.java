package edu.northeastern.cs6510.contracts.catalog.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Value of a {@code catalog.products.v1} record (005 contracts/catalog-product-event.md). The
 * record key is {@code sku}. Every SKU is republished at each new {@code catalogVersion};
 * {@code active=false} means the SKU is no longer for sale.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductEventV1(
    int schemaVersion,
    String messageId,
    String messageType,
    long catalogVersion,
    String sku,
    String name,
    long priceCents,
    boolean active) {
  public static final String TOPIC = "catalog.products.v1";
  public static final String MESSAGE_TYPE = "ProductChanged";
  public static final int SCHEMA_VERSION = 1;

  /**
   * Rejects an event a consumer must never apply.
   *
   * @throws IllegalArgumentException with the reason, so the consumer dead-letters the record
   */
  public void validate() {
    if (catalogVersion < 1) throw new IllegalArgumentException("catalogVersion must be >= 1, was " + catalogVersion);
    if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku is required");
    if (name == null || name.isBlank() || name.length() > 255) throw new IllegalArgumentException("name must be 1-255 characters");
    if (priceCents < 0) throw new IllegalArgumentException("priceCents must be >= 0, was " + priceCents);
  }
}
