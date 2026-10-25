package edu.northeastern.cs6510.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Gateway transaction routes against a stub Checkout: pass-through, transport errors and field checks (T039). */
class CheckoutContractTest {
  private HttpServer upstream;
  private final List<String> received = new CopyOnWriteArrayList<>();
  private MockMvc mvc;

  @BeforeEach
  void startStubCheckout() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(exchange.getRequestMethod() + " " + path + " " + body);
      int code;
      String reply;
      if (path.endsWith("/busy/items")) {
        code = 409;
        reply = "{\"error\":\"TRANSACTION_BUSY\",\"message\":\"Transaction busy is being completed.\"}";
      } else if (path.equals("/transactions")) {
        code = 201;
        reply = "{\"transactionId\":\"tx-1\",\"status\":\"OPEN\"}";
      } else {
        code = 200;
        reply = "{\"transactionId\":\"tx-1\",\"itemCount\":1}";
      }
      byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(code, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    upstream.start();
    mvc = gatewayFor("http://127.0.0.1:" + upstream.getAddress().getPort());
  }

  @AfterEach
  void stopStubCheckout() { upstream.stop(0); }

  private static MockMvc gatewayFor(String checkoutUrl) {
    UpstreamProxy proxy = new UpstreamProxy("http://unused", checkoutUrl, "http://unused", "http://unused");
    return MockMvcBuilders.standaloneSetup(new CheckoutRouteHandler(proxy)).build();
  }

  @Test
  void forwardsSuccessfulResponsesAndBodies() throws Exception {
    mvc.perform(post("/transactions").contentType("application/json").content("{\"stationId\":\"station-1\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
    mvc.perform(post("/transactions/tx-1/items").contentType("application/json").content("{\"sku\":\"SKU-000001\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(1));
    mvc.perform(get("/transactions/tx-1")).andExpect(status().isOk());
    mvc.perform(post("/transactions/tx-1/complete")).andExpect(status().isOk());
    assertEquals(List.of(
        "POST /transactions {\"stationId\":\"station-1\"}",
        "POST /transactions/tx-1/items {\"sku\":\"SKU-000001\"}",
        "GET /transactions/tx-1 ",
        "POST /transactions/tx-1/complete "), received);
  }

  @Test
  void passesUpstreamConflictThroughUnchanged() throws Exception {
    mvc.perform(post("/transactions/busy/items").contentType("application/json").content("{\"sku\":\"SKU-000001\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().json("{\"error\":\"TRANSACTION_BUSY\",\"message\":\"Transaction busy is being completed.\"}", true));
  }

  @Test
  void unreachableCheckoutIsServiceUnavailable() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
    gatewayFor("http://127.0.0.1:" + closedPort)
        .perform(post("/transactions").contentType("application/json").content("{\"stationId\":\"station-1\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void missingFieldsAreRejectedWithoutCallingCheckout() throws Exception {
    mvc.perform(post("/transactions").contentType("application/json").content("{\"stationId\":\" \"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MISSING_STATION_ID"));
    mvc.perform(post("/transactions/tx-1/items").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MISSING_SKU"));
    assertEquals(List.of(), received);
  }
}
