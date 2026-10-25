package edu.northeastern.cs6510.checkout.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import edu.northeastern.cs6510.contracts.Fixtures;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Consumer side of Inventory internal API v1: Checkout reads the reservation fixture and 409 bodies, tolerating new fields (R16). */
class InventoryClientContractTest {
  private static final String ID = "0192c3f4-8b7a-7cc1-9a51-2f0e5d6c1a90";
  private HttpServer server;

  @AfterEach
  void stop() { if (server != null) server.stop(0); }

  @Test
  void readsTheReservationFixtureAndTheConflictBody() throws Exception {
    ObjectNode conflict = Fixtures.JSON.createObjectNode();
    conflict.put("error", "RESERVATION_NOT_COMMITTABLE").put("message", "released").put("futureField", 1);
    conflict.set("reservation", Fixtures.read("reservation-v1.json"));

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/reservations/" + ID, exchange -> {
      boolean commit = exchange.getRequestURI().getPath().endsWith("/commit");
      byte[] bytes = (commit ? conflict.toString() : Fixtures.withUnknownField("reservation-v1.json")).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(commit ? 409 : 201, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    HttpInventoryClient client = client("http://127.0.0.1:" + server.getAddress().getPort());

    InventoryClient.Ok ok = assertInstanceOf(InventoryClient.Ok.class,
        client.reserve(ID, new ReserveRequestV1("tx-1", List.of(new ReserveLineV1("SKU-000001", 3)))));
    assertEquals(ReservationStateV1.RESERVED, ok.reservation().state());
    assertEquals(2, ok.reservation().lines().get(0).reservedQuantity());

    InventoryClient.Conflict c = assertInstanceOf(InventoryClient.Conflict.class, client.commit(ID));
    assertEquals("RESERVATION_NOT_COMMITTABLE", c.code());
    assertEquals(ID, c.reservation().attemptId());
  }

  @Test
  void unreachableInventoryIsAnUnknownOutcomeNeverNotFound() throws Exception {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
    HttpInventoryClient client = client("http://127.0.0.1:" + closed);
    assertThrows(InventoryUnavailableException.class, () -> client.get(ID));
  }

  private static HttpInventoryClient client(String url) {
    return new HttpInventoryClient(RestClient.builder(), new ObjectMapper().findAndRegisterModules(), url);
  }
}
