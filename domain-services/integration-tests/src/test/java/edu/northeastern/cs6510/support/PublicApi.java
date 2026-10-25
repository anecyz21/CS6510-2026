package edu.northeastern.cs6510.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Typed calls for the seven public operations through the gateway, as the load client makes them. */
public final class PublicApi {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String base;

  public PublicApi() { this(Stack.GATEWAY); }

  public PublicApi(String base) { this.base = base; }

  /** Status plus parsed JSON body (a missing or non-JSON body becomes a null node). */
  public record Response(int status, JsonNode body) {
    public String error() { return body == null || !body.has("error") ? null : body.get("error").asText(); }
    public String text(String field) { return body == null || !body.has(field) ? null : body.get(field).asText(); }
  }

  public Response items() { return get("/items"); }

  public Response start(String stationId) { return post("/transactions", json("stationId", stationId)); }

  public Response scan(String transactionId, String sku) {
    return post("/transactions/" + transactionId + "/items", json("sku", sku));
  }

  public Response complete(String transactionId) { return post("/transactions/" + transactionId + "/complete", null); }

  public Response transaction(String transactionId) { return get("/transactions/" + transactionId); }

  public Response lowStock(Integer threshold) {
    return get("/inventory/low-stock" + (threshold == null ? "" : "?threshold=" + threshold));
  }

  public Response popularItems(Integer limit) {
    return get("/analytics/popular-items" + (limit == null ? "" : "?limit=" + limit));
  }

  public Response get(String path) { return send(HttpRequest.newBuilder(uri(path)).GET()); }

  public Response post(String path, String jsonBody) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
    if (jsonBody == null) request.POST(HttpRequest.BodyPublishers.noBody());
    else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    return send(request);
  }

  private Response send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
      JsonNode body = null;
      if (!response.body().isBlank()) {
        try {
          body = JSON.readTree(response.body());
        } catch (IOException notJson) {
          body = null;
        }
      }
      return new Response(response.statusCode(), body);
    } catch (IOException e) {
      throw new IllegalStateException("gateway request failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private URI uri(String path) { return URI.create(base + path); }

  private static String json(String field, String value) {
    return JSON.createObjectNode().put(field, value).toString();
  }
}
