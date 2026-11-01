package edu.northeastern.cs6510.checkout.scan;

import edu.northeastern.cs6510.checkout.catalog.CatalogProjection;
import org.springframework.stereotype.Component;

/**
 * Resolves a scanned SKU from the local catalog copy only (005 research R3): Checkout never asks
 * Catalog. A hit is accepted in any state, so scans of known products keep working while Kafka or
 * Catalog is away. A miss is "unknown" only once the copy has caught up with the topic; while it is
 * still replaying the product may simply not have arrived yet, so the miss is "unverifiable"
 * (503, 004 amendment A6), never "not found" (FR-029). Readiness alone would not stop such requests.
 */
@Component
public class ProductLookup {
  private final CatalogProjection projection;

  public ProductLookup(CatalogProjection projection) { this.projection = projection; }

  public Result lookup(String sku) {
    var hit = projection.find(sku);
    if (hit.isPresent()) return new Accept(hit.get().name(), hit.get().priceCents());
    return projection.caughtUp() ? new UnknownSku() : new Unverifiable();
  }

  public sealed interface Result permits Accept, UnknownSku, Unverifiable { }
  public record Accept(String name, long priceCents) implements Result { }
  public record UnknownSku() implements Result { }
  public record Unverifiable() implements Result { }
}
