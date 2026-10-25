package edu.northeastern.cs6510.boundaries;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Quickstart V3 (A6, FR-005): an unreachable owner becomes 503 SERVICE_UNAVAILABLE at the gateway, never a 500 or a guess. */
@Tag("system")
class GatewayUnavailableTest {
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void stoppedCheckoutAndCatalogAreReportedAsUnavailable() {
    try {
      Stack.stop("checkout");
      assertUnavailable(api.start("gateway-503"));

      Stack.stop("catalog");
      assertUnavailable(api.items());
    } finally {
      Stack.start("checkout");
      Stack.start("catalog");
      Stack.awaitReady("checkout", Duration.ofSeconds(180));
      Stack.awaitReady("catalog", Duration.ofSeconds(180));
    }
    assertEquals(201, api.start("gateway-back").status(), "routes recover once the owner is back");
    assertEquals(200, api.items().status());
  }

  private static void assertUnavailable(Response response) {
    assertEquals(503, response.status(), String.valueOf(response.body()));
    assertEquals("SERVICE_UNAVAILABLE", response.error());
    List<String> fields = new ArrayList<>();
    response.body().fieldNames().forEachRemaining(fields::add);
    assertEquals(List.of("error", "message"), fields.stream().sorted().toList(), "only the ApiError fields");
  }
}
