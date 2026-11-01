package edu.northeastern.cs6510.catalog.ops;

import edu.northeastern.cs6510.catalog.CatalogRepository;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator republish of the catalog (005 research R2, contracts/catalog-product-event.md): forgets
 * the published version so the next {@code ProductPublisher} pass, within 5 s, publishes every item
 * again. Consumers ignore records they already hold. Not routed by the gateway.
 */
@RestController
public class CatalogOpsController {
  private final CatalogRepository repository;

  public CatalogOpsController(CatalogRepository repository) { this.repository = repository; }

  @PostMapping("/internal/v1/ops/republish")
  public ResponseEntity<Map<String, Object>> republish() {
    repository.resetPublication();
    return ResponseEntity.accepted().body(Map.of("catalogVersion", repository.catalogVersion()));
  }
}
