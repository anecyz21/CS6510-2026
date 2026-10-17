package edu.northeastern.cs6510.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Contract inventory: the route handler is intentionally transport-only. */
class ReadModelContractTest {
  @Test void exposesAllReadModelRoutes() throws Exception {
    assertTrue(ReadModelRouteHandler.class.getDeclaredMethod("items") != null);
    assertTrue(ReadModelRouteHandler.class.getDeclaredMethod("lowStock", int.class) != null);
    assertTrue(ReadModelRouteHandler.class.getDeclaredMethod("popular", int.class) != null);
  }
}
