package edu.northeastern.cs6510.checkout.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether Inventory is reachable, as detail under {@code inventory} (research R17). It is
 * not part of the readiness group: Checkout is ready with Inventory stopped (US3-1), and completion
 * then answers 503 truthfully.
 */
@Component
public class InventoryHealthIndicator implements HealthIndicator {
  private static final Duration TIMEOUT = Duration.ofMillis(300);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final URI readiness;

  public InventoryHealthIndicator(@Value("${inventory.url}") String inventoryUrl) {
    this.readiness = URI.create(inventoryUrl.replaceAll("/+$", "") + "/actuator/health/readiness");
  }

  @Override
  public Health health() {
    try {
      HttpResponse<Void> response = http.send(HttpRequest.newBuilder(readiness).timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200 ? Health.up().build() : Health.down().withDetail("status", response.statusCode()).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Health.down().withDetail("error", "interrupted").build();
    } catch (Exception e) {
      return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
    }
  }
}
