package edu.northeastern.cs6510.inventory.catalog;

import edu.northeastern.cs6510.contracts.catalog.v1.CatalogVersionV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the name projection fresh: probes Catalog's version on a fixed delay (starting at once, on
 * the scheduler thread, so readiness never waits for Catalog) and reloads every name when the
 * version changed, nothing was loaded yet, or the rows were deleted.
 */
@Component
public class CatalogNameProjectionLoader {
  private static final Logger log = LoggerFactory.getLogger(CatalogNameProjectionLoader.class);
  private static final int PAGE_SIZE = 1000;
  private static final int MAX_LOAD_RESTARTS = 3;

  private final CatalogClient catalog;
  private final CatalogNameProjection projection;

  public CatalogNameProjectionLoader(CatalogClient catalog, CatalogNameProjection projection) {
    this.catalog = catalog;
    this.projection = projection;
  }

  @Scheduled(initialDelay = 0, fixedDelayString = "${catalog.projection.poll-interval:15s}")
  public void refresh() {
    if (projection.loadedVersion() != null && projection.storedRowCount() == 0) {
      log.warn("catalog name rows were deleted; forgetting names until they are reloaded");
      projection.clear();
    }
    CatalogVersionV1 current;
    try {
      current = catalog.version();
    } catch (CatalogClient.CatalogUnavailableException e) {
      log.debug("catalog version probe failed: {}", e.getMessage());
      return;
    }
    Long loaded = projection.loadedVersion();
    if (loaded != null && loaded == current.catalogVersion() && projection.storedRowCount() > 0) {
      projection.markVerified();
      return;
    }
    for (int attempt = 1; attempt <= MAX_LOAD_RESTARTS; attempt++) {
      try {
        List<ProductPageV1.Item> items = new ArrayList<>();
        ProductPageV1 page = catalog.page(null, PAGE_SIZE);
        long version = page.catalogVersion();
        boolean consistent = true;
        while (true) {
          if (page.catalogVersion() != version) { consistent = false; break; }
          items.addAll(page.items());
          if (page.nextAfterSku() == null) break;
          page = catalog.page(page.nextAfterSku(), PAGE_SIZE);
        }
        if (consistent) {
          projection.replaceAll(version, items);
          log.info("catalog name projection loaded: version {}, {} products", version, items.size());
          return;
        }
      } catch (CatalogClient.CatalogUnavailableException e) {
        log.warn("catalog name projection load failed: {}", e.getMessage());
        return;
      }
    }
  }
}
