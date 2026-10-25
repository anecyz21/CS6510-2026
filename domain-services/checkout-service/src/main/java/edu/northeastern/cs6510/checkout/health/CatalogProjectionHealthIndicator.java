package edu.northeastern.cs6510.checkout.health;

import edu.northeastern.cs6510.checkout.catalog.CatalogProjection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the catalog projection's freshness as detail under {@code catalogProjection}. Always UP:
 * a STALE or EMPTY projection is handled by the documented scan policy, never by failing health.
 */
@Component
public class CatalogProjectionHealthIndicator implements HealthIndicator {
  private final CatalogProjection projection;

  public CatalogProjectionHealthIndicator(CatalogProjection projection) { this.projection = projection; }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("state", projection.state().name());
    details.put("catalogVersion", projection.loadedVersion());
    details.put("lastVerifiedAt", projection.lastVerifiedAt() == null ? null : projection.lastVerifiedAt().toString());
    return Health.up().withDetails(details).build();
  }
}
