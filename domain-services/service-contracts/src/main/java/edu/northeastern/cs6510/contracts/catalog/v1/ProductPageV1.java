package edu.northeastern.cs6510.contracts.catalog.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * {@code GET /internal/v1/products}: one page of active products in ascending SKU order.
 * {@code nextAfterSku} is null on the last page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductPageV1(long catalogVersion, List<Item> items, String nextAfterSku) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(String sku, String name, long priceCents) { }
}
