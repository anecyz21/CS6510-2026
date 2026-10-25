package edu.northeastern.cs6510.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Transport-only forwarding to the owning service (FR-005, research R12). The upstream status,
 * Content-Type and body are returned unchanged, including every 4xx, so domain error codes reach
 * the client. An unreachable or timed-out upstream becomes 503 SERVICE_UNAVAILABLE.
 *
 * <p>Measured inside the Docker network, this hop adds well under 1 ms per request. The ~40 ms
 * stall seen at the gateway on POSTs with a body (baseline defect F12) comes from Docker Desktop's
 * port forwarding for ports published on all interfaces, not from this client; see
 * reports/redesign/GATEWAY-HOP.md.
 */
@Component
public class UpstreamProxy {
  public enum Service { CATALOG, CHECKOUT, INVENTORY, ANALYTICS }

  /** Longer than Checkout's 5 s completion deadline, so clients receive Checkout's own 503 (R9). */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(1))
      .version(HttpClient.Version.HTTP_1_1)
      .build();
  private final Map<Service, String> baseUrls = new EnumMap<>(Service.class);

  public UpstreamProxy(@Value("${services.catalog-url:http://localhost:8081}") String catalogUrl,
                       @Value("${services.checkout-url:http://localhost:8082}") String checkoutUrl,
                       @Value("${services.inventory-url:http://localhost:8083}") String inventoryUrl,
                       @Value("${services.analytics-url:http://localhost:8084}") String analyticsUrl) {
    baseUrls.put(Service.CATALOG, trim(catalogUrl));
    baseUrls.put(Service.CHECKOUT, trim(checkoutUrl));
    baseUrls.put(Service.INVENTORY, trim(inventoryUrl));
    baseUrls.put(Service.ANALYTICS, trim(analyticsUrl));
  }

  /**
   * Forwards one request. {@code pathAndQuery} starts with "/" and may carry the original query
   * string; {@code body} may be null.
   */
  public ResponseEntity<byte[]> forward(Service service, HttpMethod method, String pathAndQuery, byte[] body, String contentType) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrls.get(service) + pathAndQuery))
        .timeout(READ_TIMEOUT)
        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    if (body != null && body.length > 0) {
      request.header(HttpHeaders.CONTENT_TYPE, contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType);
      request.method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body));
    } else {
      request.method(method.name(), HttpRequest.BodyPublishers.noBody());
    }
    try {
      HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      ResponseEntity.BodyBuilder out = ResponseEntity.status(response.statusCode());
      response.headers().firstValue(HttpHeaders.CONTENT_TYPE).ifPresent(type -> out.header(HttpHeaders.CONTENT_TYPE, type));
      return out.body(response.body());
    } catch (IOException e) {
      // Connection refused, reset or timed out (HttpTimeoutException is an IOException).
      return unavailable(service);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unavailable(service);
    }
  }

  static ResponseEntity<byte[]> unavailable(Service service) {
    String name = service.name().toLowerCase();
    String body = "{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"The " + name + " service is unavailable.\"}";
    return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
  }

  private static String trim(String url) { return url.endsWith("/") ? url.substring(0, url.length() - 1) : url; }
}
