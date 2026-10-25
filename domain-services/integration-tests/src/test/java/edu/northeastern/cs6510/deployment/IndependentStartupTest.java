package edu.northeastern.cs6510.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IndependentStartupTest {
  @Test void everyDeployableHasReadinessAndOwnDatasource() throws Exception {
    for (String service : new String[]{"catalog-service", "checkout-service", "inventory-service", "analytics-service"}) {
      String config=Files.readString(Path.of("..", service, "src/main/resources/application.yml"));
      assertTrue(config.contains("datasource:"));
      // Health and readiness come from Spring Boot actuator (application.yml), not hand-written controllers.
      assertTrue(config.contains("readiness:"));
    }
  }
}
