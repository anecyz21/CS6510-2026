package edu.northeastern.cs6510.gateway;

import java.util.Map;

/** Transport boundary used only by the gateway to reach the Checkout service. */
public interface CheckoutClient {
  Map<String, Object> start(String stationId);
  Map<String, Object> scan(String transactionId, String sku);
  Map<String, Object> get(String transactionId);
  Map<String, Object> complete(String transactionId);
}
