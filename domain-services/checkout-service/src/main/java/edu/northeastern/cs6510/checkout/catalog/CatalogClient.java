package edu.northeastern.cs6510.checkout.catalog;

import edu.northeastern.cs6510.contracts.catalog.v1.CatalogVersionV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductV1;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Catalog internal API v1 client (contracts/catalog-internal-api.md). A transport failure or 5xx is
 * always "unavailable", never "not found" (FR-029).
 */
@Component
public class CatalogClient {
  private final RestClient snapshot;
  private final RestClient lookup;

  public CatalogClient(RestClient.Builder builder, @Value("${catalog.url}") String catalogUrl) {
    this.snapshot = client(builder, catalogUrl, Duration.ofMillis(300), Duration.ofSeconds(2));
    // The stale-miss lookup is on the scan path, so it gets a tight budget.
    this.lookup = client(builder, catalogUrl, Duration.ofMillis(300), Duration.ofMillis(300));
  }

  private static RestClient client(RestClient.Builder builder, String baseUrl, Duration connect, Duration read) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(connect).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(read);
    return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
  }

  public CatalogVersionV1 version() {
    try {
      return snapshot.get().uri("/internal/v1/catalog-version").retrieve().body(CatalogVersionV1.class);
    } catch (RestClientException e) {
      throw new CatalogUnavailableException(e);
    }
  }

  public ProductPageV1 page(String afterSku, int limit) {
    try {
      return snapshot.get()
          .uri(b -> {
            b.path("/internal/v1/products").queryParam("limit", limit);
            if (afterSku != null) b.queryParam("afterSku", afterSku);
            return b.build();
          })
          .retrieve().body(ProductPageV1.class);
    } catch (RestClientException e) {
      throw new CatalogUnavailableException(e);
    }
  }

  public Lookup lookup(String sku) {
    try {
      ProductV1 product = lookup.get().uri("/internal/v1/products/{sku}", sku).retrieve().body(ProductV1.class);
      return product == null ? new Unavailable("empty response") : new Found(product);
    } catch (HttpClientErrorException.NotFound e) {
      return new NotFound();
    } catch (RestClientException e) {
      return new Unavailable(e.getMessage());
    }
  }

  /** Result of a single-SKU lookup. */
  public sealed interface Lookup permits Found, NotFound, Unavailable { }
  public record Found(ProductV1 product) implements Lookup { }
  public record NotFound() implements Lookup { }
  public record Unavailable(String reason) implements Lookup { }

  /** Catalog could not be reached or answered with a server error. */
  public static class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException(Throwable cause) { super("catalog unavailable: " + cause.getMessage(), cause); }
  }
}
