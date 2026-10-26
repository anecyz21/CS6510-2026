package edu.northeastern.cs6510.inventory.catalog;

import edu.northeastern.cs6510.contracts.catalog.v1.CatalogVersionV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Catalog internal API v1, used only to keep the name projection current (research R7). */
@Component
public class CatalogClient {
  private final RestClient http;

  public CatalogClient(RestClient.Builder builder, @Value("${catalog.url}") String catalogUrl) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(2));
    this.http = builder.clone().baseUrl(catalogUrl).requestFactory(factory).build();
  }

  public CatalogVersionV1 version() {
    try {
      return http.get().uri("/internal/v1/catalog-version").retrieve().body(CatalogVersionV1.class);
    } catch (RestClientException | CancellationException e) {
      throw new CatalogUnavailableException(e);
    }
  }

  public ProductPageV1 page(String afterSku, int limit) {
    try {
      return http.get().uri(b -> {
        b.path("/internal/v1/products").queryParam("limit", limit);
        if (afterSku != null) b.queryParam("afterSku", afterSku);
        return b.build();
      }).retrieve().body(ProductPageV1.class);
    } catch (RestClientException | CancellationException e) {
      throw new CatalogUnavailableException(e);
    }
  }

  /** Catalog could not be reached, timed out, or answered with a server error. */
  public static class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException(Throwable cause) { super("catalog unavailable: " + cause.getMessage(), cause); }
  }
}
