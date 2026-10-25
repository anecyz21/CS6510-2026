package edu.northeastern.cs6510.catalog.internal;

import edu.northeastern.cs6510.catalog.CatalogRepository;
import edu.northeastern.cs6510.catalog.CatalogRepository.CatalogItem;
import edu.northeastern.cs6510.catalog.api.ApiException;
import edu.northeastern.cs6510.contracts.catalog.v1.CatalogVersionV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductV1;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog internal API v1 (contracts/catalog-internal-api.md). Read-only and safe to retry; not
 * routed by the gateway. Consumers build their local projections from it.
 */
@RestController
@RequestMapping("/internal/v1")
public class CatalogInternalController {
  private static final int MAX_LIMIT = 1000;
  private final CatalogRepository repository;

  public CatalogInternalController(CatalogRepository repository) { this.repository = repository; }

  @GetMapping("/catalog-version")
  public CatalogVersionV1 version() {
    CatalogRepository.CatalogVersion v = repository.version();
    return new CatalogVersionV1(v.catalogVersion(), v.updatedAt(), v.activeItemCount());
  }

  /**
   * Pages active products in ascending SKU order. The version is read before the page, so a
   * consumer that sees the same version on every page holds a consistent snapshot.
   */
  @GetMapping("/products")
  public ProductPageV1 products(@RequestParam(required = false) String afterSku,
                                @RequestParam(defaultValue = "500") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) throw ApiException.invalidLimit(limit);
    long catalogVersion = repository.version().catalogVersion();
    List<CatalogItem> rows = repository.activeItemsAfter(afterSku, limit + 1);
    boolean more = rows.size() > limit;
    List<CatalogItem> page = more ? rows.subList(0, limit) : rows;
    List<ProductPageV1.Item> items = page.stream()
        .map(i -> new ProductPageV1.Item(i.sku(), i.name(), i.priceCents())).toList();
    return new ProductPageV1(catalogVersion, items, more ? page.get(page.size() - 1).sku() : null);
  }

  /** One active product, or 404 SKU_NOT_FOUND when it is missing or inactive. */
  @GetMapping("/products/{sku}")
  public ProductV1 product(@PathVariable String sku) {
    long catalogVersion = repository.version().catalogVersion();
    CatalogItem item = repository.activeItem(sku).orElseThrow(() -> ApiException.skuNotFound(sku));
    return new ProductV1(catalogVersion, item.sku(), item.name(), item.priceCents());
  }
}
