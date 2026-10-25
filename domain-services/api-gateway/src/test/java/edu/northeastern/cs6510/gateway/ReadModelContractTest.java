package edu.northeastern.cs6510.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Gateway read routes: each goes to its owner with the query string intact; INVALID_LIMIT stays at the gateway (T039). */
class ReadModelContractTest {
  private HttpServer upstream;
  private final List<String> received = new CopyOnWriteArrayList<>();
  private MockMvc mvc;

  @BeforeEach
  void startStubOwners() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", exchange -> {
      received.add(exchange.getRequestURI().toString());
      byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    upstream.start();
    String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
    mvc = MockMvcBuilders.standaloneSetup(new ReadModelRouteHandler(new UpstreamProxy(url, url, url, url))).build();
  }

  @AfterEach
  void stopStubOwners() { upstream.stop(0); }

  @Test
  void forwardsAllThreeReadRoutesWithTheirQueries() throws Exception {
    mvc.perform(get("/items")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    mvc.perform(get("/inventory/low-stock").queryParam("threshold", "5")).andExpect(status().isOk());
    mvc.perform(get("/analytics/popular-items").queryParam("limit", "3")).andExpect(status().isOk());
    mvc.perform(get("/analytics/popular-items")).andExpect(status().isOk());
    assertEquals(List.of("/items", "/inventory/low-stock?threshold=5", "/analytics/popular-items?limit=3", "/analytics/popular-items"), received);
  }

  @Test
  void invalidLimitIsRejectedAtTheGateway() throws Exception {
    mvc.perform(get("/analytics/popular-items").queryParam("limit", "0"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_LIMIT"));
    assertEquals(List.of(), received);
  }
}
