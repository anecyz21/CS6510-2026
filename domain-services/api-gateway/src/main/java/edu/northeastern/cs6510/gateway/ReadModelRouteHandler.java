package edu.northeastern.cs6510.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The three public read routes, forwarded unchanged (query string included) to their owners. */
@RestController
public class ReadModelRouteHandler {
  private final UpstreamProxy proxy;

  public ReadModelRouteHandler(UpstreamProxy proxy) { this.proxy = proxy; }

  @GetMapping("/items")
  ResponseEntity<byte[]> items(HttpServletRequest request) {
    return proxy.forward(UpstreamProxy.Service.CATALOG, HttpMethod.GET, pathAndQuery(request), null, null);
  }

  @GetMapping("/inventory/low-stock")
  ResponseEntity<byte[]> lowStock(HttpServletRequest request) {
    return proxy.forward(UpstreamProxy.Service.INVENTORY, HttpMethod.GET, pathAndQuery(request), null, null);
  }

  @GetMapping("/analytics/popular-items")
  ResponseEntity<byte[]> popular(HttpServletRequest request) {
    String limit = request.getParameter("limit");
    if (limit != null) {
      int value;
      try {
        value = Integer.parseInt(limit.trim());
      } catch (NumberFormatException notANumber) {
        return GatewayValidation.badRequest("INVALID_LIMIT", "limit must be a positive integer.");
      }
      if (value < 1) return GatewayValidation.badRequest("INVALID_LIMIT", "limit must be at least 1.");
    }
    return proxy.forward(UpstreamProxy.Service.ANALYTICS, HttpMethod.GET, pathAndQuery(request), null, null);
  }

  private static String pathAndQuery(HttpServletRequest request) {
    String query = request.getQueryString();
    return request.getRequestURI() + (query == null ? "" : "?" + query);
  }
}
