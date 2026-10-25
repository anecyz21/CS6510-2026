package edu.northeastern.cs6510.checkout.inventory;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Every transport failure is an unknown outcome, never an unexpected exception (regression: read timeout leaked a 500). */
class HttpInventoryClientTest {
  private HttpServer server;

  @AfterEach
  void stop() { if (server != null) server.stop(0); }

  @Test
  void readTimeoutIsAnUnknownOutcome() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try {
        Thread.sleep(3000);   // longer than the 1,500 ms read timeout
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
    });
    server.start();
    HttpInventoryClient client = client("http://127.0.0.1:" + server.getAddress().getPort());
    assertThrows(InventoryUnavailableException.class, () -> client.commit("attempt-1"));
  }

  @Test
  void refusedConnectionIsAnUnknownOutcome() throws Exception {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
    HttpInventoryClient client = client("http://127.0.0.1:" + closed);
    assertThrows(InventoryUnavailableException.class, () -> client.commit("attempt-1"));
  }

  private static HttpInventoryClient client(String url) {
    return new HttpInventoryClient(RestClient.builder(), new ObjectMapper().findAndRegisterModules(), url);
  }
}
