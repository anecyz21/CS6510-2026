package edu.northeastern.cs6510.checkout.health;

import edu.northeastern.cs6510.checkout.catalog.CatalogProjection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Readiness of the catalog copy, under {@code catalogProjection} (005 research R3, FR-007):
 * {@code OUT_OF_SERVICE} while it is replaying, {@code UP} once it has caught up with the topic as it
 * was at assignment and holds at least one product. The scan path enforces the same rule itself.
 */
@Component
public class CatalogProjectionHealthIndicator implements HealthIndicator {
  private final CatalogProjection projection;

  public CatalogProjectionHealthIndicator(CatalogProjection projection) { this.projection = projection; }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("state", projection.state().name());
    details.put("products", projection.size());
    details.put("catalogVersion", projection.catalogVersion());
    details.put("topicOffset", projection.topicOffset());
    details.put("catchUpTarget", projection.catchUpTarget());
    return Health.status(projection.caughtUp() ? Status.UP : Status.OUT_OF_SERVICE).withDetails(details).build();
  }
}
