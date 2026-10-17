package edu.northeastern.cs6510.gateway;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** The gateway is the only HTTP caller between the public contract and domain services. */
@RestController
public class ReadModelRouteHandler {
  private final RestClient catalog;
  private final RestClient inventory;
  private final RestClient analytics;
  public ReadModelRouteHandler(@Value("${services.catalog-url:http://localhost:8081}") String catalogUrl,
      @Value("${services.inventory-url:http://localhost:8083}") String inventoryUrl,
      @Value("${services.analytics-url:http://localhost:8084}") String analyticsUrl) {
    catalog=RestClient.create(catalogUrl); inventory=RestClient.create(inventoryUrl); analytics=RestClient.create(analyticsUrl);
  }
  @SuppressWarnings("unchecked") @GetMapping("/items") Map<String,Object> items(){ return catalog.get().uri("/items").retrieve().body(Map.class); }
  @SuppressWarnings("unchecked") @GetMapping("/inventory/low-stock") Map<String,Object> lowStock(@RequestParam(defaultValue="10") int threshold){ return inventory.get().uri("/inventory/low-stock?threshold={threshold}",threshold).retrieve().body(Map.class); }
  @SuppressWarnings("unchecked") @GetMapping("/analytics/popular-items") Map<String,Object> popular(@RequestParam(defaultValue="10") int limit){ return analytics.get().uri("/analytics/popular-items?limit={limit}",limit).retrieve().body(Map.class); }
}
