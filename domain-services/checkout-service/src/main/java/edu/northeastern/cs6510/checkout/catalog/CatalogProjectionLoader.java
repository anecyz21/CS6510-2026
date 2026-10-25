package edu.northeastern.cs6510.checkout.catalog;

import edu.northeastern.cs6510.contracts.catalog.v1.CatalogVersionV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the catalog projection fresh (research R6): probes Catalog's version on a fixed delay,
 * starting immediately on the scheduler thread so it never blocks readiness, and reloads every
 * product when the version changes, the projection was never loaded, or its rows were deleted.
 */
@Component
public class CatalogProjectionLoader {
  private static final Logger log = LoggerFactory.getLogger(CatalogProjectionLoader.class);
  private static final int PAGE_SIZE = 1000;
  private static final int MAX_LOAD_RESTARTS = 3;

  private final CatalogClient catalog;
  private final CatalogProjection projection;

  public CatalogProjectionLoader(CatalogClient catalog, CatalogProjection projection) {
    this.catalog = catalog;
    this.projection = projection;
  }

  @Scheduled(initialDelay = 0, fixedDelayString = "${catalog.projection.poll-interval:15s}")
  public void refresh() {
    CatalogVersionV1 current;
    try {
      current = catalog.version();
    } catch (CatalogClient.CatalogUnavailableException e) {
      // Leave the projection and last_verified_at unchanged; it may become STALE.
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
          log.info("catalog projection loaded: version {}, {} products", version, items.size());
          return;
        }
        log.info("catalog version changed during load; restarting ({}/{})", attempt, MAX_LOAD_RESTARTS);
      } catch (CatalogClient.CatalogUnavailableException e) {
        log.warn("catalog projection load failed: {}", e.getMessage());
        return;
      }
    }
  }
}
