package edu.northeastern.cs6510.gateway;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The four public transaction routes, forwarded to Checkout after the gateway's own field checks. */
@RestController
@RequestMapping("/transactions")
public class CheckoutRouteHandler {
  private final UpstreamProxy proxy;

  public CheckoutRouteHandler(UpstreamProxy proxy) { this.proxy = proxy; }

  @PostMapping
  ResponseEntity<byte[]> start(@RequestBody(required = false) byte[] body,
                               @RequestHeader(value = "Content-Type", required = false) String contentType) {
    if (GatewayValidation.missingText(body, "stationId")) {
      return GatewayValidation.badRequest("MISSING_STATION_ID", "stationId is required.");
    }
    return proxy.forward(UpstreamProxy.Service.CHECKOUT, HttpMethod.POST, "/transactions", body, contentType);
  }

  @PostMapping("/{transactionId}/items")
  ResponseEntity<byte[]> scan(@PathVariable String transactionId, @RequestBody(required = false) byte[] body,
                              @RequestHeader(value = "Content-Type", required = false) String contentType) {
    if (GatewayValidation.missingText(body, "sku")) {
      return GatewayValidation.badRequest("MISSING_SKU", "sku is required.");
    }
    return proxy.forward(UpstreamProxy.Service.CHECKOUT, HttpMethod.POST, "/transactions/" + transactionId + "/items", body, contentType);
  }

  @GetMapping("/{transactionId}")
  ResponseEntity<byte[]> get(@PathVariable String transactionId) {
    return proxy.forward(UpstreamProxy.Service.CHECKOUT, HttpMethod.GET, "/transactions/" + transactionId, null, null);
  }

  @PostMapping("/{transactionId}/complete")
  ResponseEntity<byte[]> complete(@PathVariable String transactionId) {
    return proxy.forward(UpstreamProxy.Service.CHECKOUT, HttpMethod.POST, "/transactions/" + transactionId + "/complete", null, null);
  }
}
