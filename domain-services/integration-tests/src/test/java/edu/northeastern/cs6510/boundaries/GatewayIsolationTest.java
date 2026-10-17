package edu.northeastern.cs6510.boundaries;

import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GatewayIsolationTest {
  @Test void gatewayHasNoDatabaseDependency() throws Exception {
    String pom = Files.readString(Path.of("../api-gateway/pom.xml"));
    assertFalse(pom.contains("starter-jdbc"));
    assertFalse(pom.contains("mysql-connector"));
  }
}
