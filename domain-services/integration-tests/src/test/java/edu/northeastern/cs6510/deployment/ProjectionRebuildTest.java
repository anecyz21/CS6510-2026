package edu.northeastern.cs6510.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Stack;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * 005 quickstart Q1, US3 scenario 4, FR-006/FR-007: a catalog copy rebuilt from scratch replays
 * catalog.products.v1 from the beginning, and is CURRENT again with every catalog item, at the
 * topic's end offset.
 */
@Tag("system")
class ProjectionRebuildTest {
  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"checkout", "inventory", "analytics"})
  void aRebuiltCopyReplaysToTheTopicEnd(String service) {
    int catalogItems = new PublicApi().items().body().get("items").size();
    PublicApi operator = new PublicApi(Stack.SERVICE_URLS.get(service));

    PublicApi.Response rebuild = operator.post("/internal/v1/ops/catalog-projection/rebuild", null);
    assertEquals(202, rebuild.status(), String.valueOf(rebuild.body()));
    Stack.awaitProjectionCurrent(service, Duration.ofSeconds(60));

    JsonNode projection = operator.get("/actuator/health").body().get("components").get("catalogProjection").get("details");
    int held = projection.has("products") ? projection.get("products").asInt() : projection.get("names").asInt();
    assertEquals(catalogItems, held, service + " holds every active catalog item again");
    assertTrue(projection.get("topicOffset").asLong() + 1 >= projection.get("catchUpTarget").asLong(), "caught up: " + projection);
  }
}
