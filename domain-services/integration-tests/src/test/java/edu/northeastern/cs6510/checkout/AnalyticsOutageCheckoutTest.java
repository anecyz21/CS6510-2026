package edu.northeastern.cs6510.checkout;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Run with Analytics intentionally stopped and RUN_CHECKOUT_DATABASE_TESTS=true. */
@EnabledIfEnvironmentVariable(named = "RUN_CHECKOUT_DATABASE_TESTS", matches = "true")
class AnalyticsOutageCheckoutTest {
  @Test void acceptedScanPersistsBasketAndOutboxWithoutAnalyticsProcess() throws Exception {
    String baseUrl = required("GATEWAY_URL");
    String transaction = post(baseUrl + "/transactions", "{\"stationId\":\"outage-test\"}");
    String transactionId = transaction.replaceAll(".*\\\"transactionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    post(baseUrl + "/transactions/" + transactionId + "/items", "{\"sku\":\"" + required("OUTAGE_TEST_SKU") + "\"}");
    try (Connection connection = database(); var statement = connection.createStatement()) {
      ResultSet result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM checkout.basket_lines WHERE transaction_id = '" + transactionId + "') = 1 AND (SELECT COUNT(*) FROM checkout.accepted_scan_events WHERE transaction_id = '" + transactionId + "') = 1");
      result.next();
      assertTrue(result.getBoolean(1), "basket change and durable outbox record must commit together");
    }
  }

  @Test void checkoutSourceHasNoAnalyticsTransportDependency() throws Exception {
    String source = Files.readString(Path.of("../checkout-service/src/main/java/edu/northeastern/cs6510/checkout/CheckoutApplication.java"));
    assertTrue(!source.contains("analytics") && !source.contains("Analytics"));
  }

  private String post(String url, String body) throws Exception {
    HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new AssertionError("request failed: " + response.statusCode() + " " + response.body());
    return response.body();
  }

  private Connection database() throws Exception { return DriverManager.getConnection(required("CHECKOUT_TEST_DB_URL"), required("CHECKOUT_TEST_DB_USERNAME"), required("CHECKOUT_TEST_DB_PASSWORD")); }
  private String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set"); return value; }
}
