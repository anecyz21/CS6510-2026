package edu.northeastern.cs6510.inventory.lowstock;

import edu.northeastern.cs6510.inventory.api.ApiException;
import edu.northeastern.cs6510.inventory.catalog.CatalogNameProjection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /inventory/low-stock}: SKUs whose physical stock is below the threshold, with names from
 * the local catalog projection. A missing name is a 503, never the SKU in its place (finding F4).
 */
@RestController
public class LowStockController {
  private final LowStockRepository repository;
  private final CatalogNameProjection names;
  private final int defaultThreshold;

  public LowStockController(LowStockRepository repository, CatalogNameProjection names,
                            @Value("${inventory.low-stock.threshold:10}") int defaultThreshold) {
    this.repository = repository;
    this.names = names;
    this.defaultThreshold = defaultThreshold;
  }

  public record LowStockAlert(String sku, String name, int currentStock, int threshold, String triggeredAt) { }

  @GetMapping("/inventory/low-stock")
  @Transactional(readOnly = true)
  public Map<String, Object> lowStock(@RequestParam(required = false) Integer threshold) {
    int limit = threshold == null ? defaultThreshold : threshold;
    Instant now = Instant.now();
    List<LowStockRepository.Position> positions = repository.below(limit);
    Map<String, Instant> triggered = positions.isEmpty() ? Map.of() : repository.lastSaleBelow(limit);
    List<LowStockAlert> alerts = new ArrayList<>(positions.size());
    for (LowStockRepository.Position p : positions) {
      String name = names.name(p.sku()).orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
          "SERVICE_UNAVAILABLE", "Product names are not available yet; try again shortly."));
      alerts.add(new LowStockAlert(p.sku(), name, p.physicalStock(), limit, triggered.getOrDefault(p.sku(), now).toString()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("threshold", limit);
    body.put("generatedAt", now.toString());
    body.put("alerts", alerts);
    return body;
  }
}
