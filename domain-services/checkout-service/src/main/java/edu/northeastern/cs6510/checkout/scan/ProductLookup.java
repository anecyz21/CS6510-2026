package edu.northeastern.cs6510.checkout.scan;

import edu.northeastern.cs6510.checkout.catalog.CatalogClient;
import edu.northeastern.cs6510.checkout.catalog.CatalogProjection;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductV1;
import org.springframework.stereotype.Component;

/**
 * Resolves a scanned SKU by the projection policy in contracts/catalog-internal-api.md:
 * a projection hit is accepted in any state; a miss is unknown only while the projection is
 * CURRENT; otherwise Catalog is asked once, and an unreachable Catalog is "unverifiable", never
 * "not found" (FR-029). Never called inside a database transaction.
 */
@Component
public class ProductLookup {
  private final CatalogProjection projection;
  private final CatalogClient catalog;

  public ProductLookup(CatalogProjection projection, CatalogClient catalog) {
    this.projection = projection;
    this.catalog = catalog;
  }

  public Result lookup(String sku) {
    var hit = projection.find(sku);
    if (hit.isPresent()) return new Accept(hit.get().name(), hit.get().priceCents());
    if (projection.state() == CatalogProjection.State.CURRENT) return new UnknownSku();
    return switch (catalog.lookup(sku)) {
      case CatalogClient.Found found -> {
        ProductV1 product = found.product();
        projection.upsertSingle(product);
        yield new Accept(product.name(), product.priceCents());
      }
      case CatalogClient.NotFound notFound -> new UnknownSku();
      case CatalogClient.Unavailable unavailable -> new Unverifiable();
    };
  }

  public sealed interface Result permits Accept, UnknownSku, Unverifiable { }
  public record Accept(String name, long priceCents) implements Result { }
  public record UnknownSku() implements Result { }
  public record Unverifiable() implements Result { }
}
