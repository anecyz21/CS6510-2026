package edu.northeastern.cs6510.checkout.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import edu.northeastern.cs6510.contracts.Fixtures;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Consumer side of Catalog internal API v1: Checkout reads the fixtures even with unknown fields added (R16, FR-029). */
class CatalogClientContractTest {
  private HttpServer server;

  @AfterEach
  void stop() { if (server != null) server.stop(0); }

  @Test
  void readsEveryFixtureWithAnUnknownFieldAdded() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serve("/internal/v1/catalog-version", 200, Fixtures.withUnknownField("catalog-version-v1.json"));
    serve("/internal/v1/products", 200, Fixtures.withUnknownField("product-page-v1.json"));
    serve("/internal/v1/products/SKU-000123", 200, Fixtures.withUnknownField("product-v1.json"));
    serve("/internal/v1/products/SKU-999999", 404, "{\"error\":\"SKU_NOT_FOUND\",\"message\":\"SKU SKU-999999 is not in the catalog.\"}");
    server.start();
    CatalogClient client = client("http://127.0.0.1:" + server.getAddress().getPort());

    assertEquals(1, client.version().catalogVersion());
    ProductPageV1 page = client.page(null, 500);
    assertEquals("SKU-000001", page.items().get(0).sku());
    assertEquals(101, page.items().get(0).priceCents());
    CatalogClient.Found found = assertInstanceOf(CatalogClient.Found.class, client.lookup("SKU-000123"));
    assertEquals(223, found.product().priceCents());
    assertInstanceOf(CatalogClient.NotFound.class, client.lookup("SKU-999999"));
  }

  @Test
  void unreachableCatalogIsUnavailableNeverNotFound() throws Exception {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
    CatalogClient client = client("http://127.0.0.1:" + closed);
    assertInstanceOf(CatalogClient.Unavailable.class, client.lookup("SKU-000123"));
    assertThrows(CatalogClient.CatalogUnavailableException.class, client::version);
  }

  @Test
  void lastPageHasNoNextCursor() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serve("/internal/v1/products", 200, "{\"catalogVersion\":1,\"items\":[],\"nextAfterSku\":null}");
    server.start();
    assertNull(client("http://127.0.0.1:" + server.getAddress().getPort()).page("SKU-002000", 500).nextAfterSku());
  }

  private void serve(String path, int status, String body) {
    server.createContext(path, exchange -> {
      if (!exchange.getRequestURI().getPath().equals(path)) {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
  }

  private static CatalogClient client(String url) { return new CatalogClient(RestClient.builder(), url); }
}
