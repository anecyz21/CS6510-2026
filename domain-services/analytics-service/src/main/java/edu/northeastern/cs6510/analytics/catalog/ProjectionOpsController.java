package edu.northeastern.cs6510.analytics.catalog;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator rebuild of the catalog copy (005 research R3, quickstart Q1): deletes the copy and its
 * position in one transaction, then replays {@code catalog.products.v1} from the beginning. The
 * service reports not ready until the replay has caught up. Not routed by the gateway.
 */
@RestController
public class ProjectionOpsController {
  private final ProductEventListener listener;
  private final CatalogNameProjection projection;

  public ProjectionOpsController(ProductEventListener listener, CatalogNameProjection projection) {
    this.listener = listener;
    this.projection = projection;
  }

  @PostMapping("/internal/v1/ops/catalog-projection/rebuild")
  public ResponseEntity<Map<String, Object>> rebuild() {
    listener.rebuild();
    return ResponseEntity.accepted().body(Map.of("state", projection.state().name(), "catchUpTarget", projection.catchUpTarget()));
  }
}
