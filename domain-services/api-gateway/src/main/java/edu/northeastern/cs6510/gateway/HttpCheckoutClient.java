package edu.northeastern.cs6510.gateway;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class HttpCheckoutClient implements CheckoutClient {
  private final RestClient client;
  HttpCheckoutClient(@Value("${services.checkout-url:http://localhost:8082}") String url) { client = RestClient.create(url); }
  @SuppressWarnings("unchecked") private Map<String,Object> call(String path, Object body) { return body == null ? client.post().uri(path).retrieve().body(Map.class) : client.post().uri(path).body(body).retrieve().body(Map.class); }
  public Map<String,Object> start(String stationId) { return call("/transactions", Map.of("stationId", stationId)); }
  public Map<String,Object> scan(String id, String sku) { return call("/transactions/" + id + "/items", Map.of("sku", sku)); }
  public Map<String,Object> get(String id) { return client.get().uri("/transactions/" + id).retrieve().body(Map.class); }
  public Map<String,Object> complete(String id) { return call("/transactions/" + id + "/complete", null); }
}
