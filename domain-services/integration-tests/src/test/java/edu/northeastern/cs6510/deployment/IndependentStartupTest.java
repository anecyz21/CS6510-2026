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
      String source=Files.walk(Path.of("..", service, "src/main/java")).filter(p->p.toString().endsWith(".java")).map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}).reduce("",String::concat);
      assertTrue(source.contains("/actuator/health"));
    }
  }
}
