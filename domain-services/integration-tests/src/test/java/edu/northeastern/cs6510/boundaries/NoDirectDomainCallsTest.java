package edu.northeastern.cs6510.boundaries;

import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps direct domain transport imports out of domain modules; gateway transport is the sole exception. */
class NoDirectDomainCallsTest {
  @Test void domainSourcesDoNotUseHttpOrBrokerClients() throws Exception {
    for (String service : new String[]{"catalog-service", "checkout-service", "inventory-service", "analytics-service"}) {
      String source = Files.walk(Path.of("..", service, "src", "main", "java")).filter(p -> p.toString().endsWith(".java")).map(p -> { try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); }}).reduce("", String::concat);
      assertFalse(source.contains("RestClient") || service.equals("api-gateway"), service + " must not use service HTTP");
      assertFalse(source.contains("Kafka") || source.contains("Rabbit"), service + " must not use a broker");
    }
  }
}
