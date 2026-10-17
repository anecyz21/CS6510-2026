package edu.northeastern.cs6510.gateway;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/transactions")
public class CheckoutRouteHandler {
  private final CheckoutClient checkout;

  public CheckoutRouteHandler(CheckoutClient checkout) { this.checkout = checkout; }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> start(@RequestBody Map<String, String> request) {
    String stationId = request.get("stationId");
    if (stationId == null || stationId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_STATION_ID");
    return checkout.start(stationId);
  }

  @PostMapping("/{transactionId}/items")
  Map<String, Object> scan(@PathVariable String transactionId, @RequestBody Map<String, String> request) {
    String sku = request.get("sku");
    if (sku == null || sku.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_SKU");
    return checkout.scan(transactionId, sku);
  }

  @GetMapping("/{transactionId}")
  Map<String, Object> get(@PathVariable String transactionId) { return checkout.get(transactionId); }

  @PostMapping("/{transactionId}/complete")
  Map<String, Object> complete(@PathVariable String transactionId) { return checkout.complete(transactionId); }
}
