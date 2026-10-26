package edu.northeastern.cs6510.inventory.health;

import edu.northeastern.cs6510.inventory.catalog.CatalogNameProjection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the name projection's freshness as detail under {@code catalogProjection}. Always UP:
 * reservations never need names, and the low-stock view answers 503 itself when a name is missing.
 */
@Component
public class CatalogProjectionHealthIndicator implements HealthIndicator {
  private final CatalogNameProjection projection;

  public CatalogProjectionHealthIndicator(CatalogNameProjection projection) { this.projection = projection; }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("state", projection.state().name());
    details.put("catalogVersion", projection.loadedVersion());
    details.put("lastVerifiedAt", projection.lastVerifiedAt() == null ? null : projection.lastVerifiedAt().toString());
    return Health.up().withDetails(details).build();
  }
}
