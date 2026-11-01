package edu.northeastern.cs6510.inventory.health;

import edu.northeastern.cs6510.inventory.catalog.CatalogNameProjection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Readiness of the name copy, under {@code catalogProjection} (005 research R3, FR-007):
 * {@code OUT_OF_SERVICE} while it is replaying, {@code UP} once it has caught up with the topic as it
 * was at assignment and holds at least one name. The low-stock view answers 503 itself for a missing name.
 */
@Component
public class CatalogProjectionHealthIndicator implements HealthIndicator {
  private final CatalogNameProjection projection;

  public CatalogProjectionHealthIndicator(CatalogNameProjection projection) { this.projection = projection; }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("state", projection.state().name());
    details.put("names", projection.size());
    details.put("catalogVersion", projection.catalogVersion());
    details.put("topicOffset", projection.topicOffset());
    details.put("catchUpTarget", projection.catchUpTarget());
    return Health.status(projection.caughtUp() ? Status.UP : Status.OUT_OF_SERVICE).withDetails(details).build();
  }
}
