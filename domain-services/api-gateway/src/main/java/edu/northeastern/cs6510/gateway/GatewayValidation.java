package edu.northeastern.cs6510.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The request checks the gateway has always made, answered in the public {@code ApiError} format.
 * Every other decision belongs to the owning service.
 */
final class GatewayValidation {
  private static final ObjectMapper JSON = new ObjectMapper();

  private GatewayValidation() { }

  /** True when the JSON body lacks a non-blank text value for {@code field}. Unreadable bodies count as missing. */
  static boolean missingText(byte[] body, String field) {
    if (body == null || body.length == 0) return true;
    try {
      JsonNode value = JSON.readTree(body).get(field);
      return value == null || !value.isTextual() || value.asText().isBlank();
    } catch (Exception unreadable) {
      return true;
    }
  }

  static ResponseEntity<byte[]> badRequest(String code, String message) {
    String body = "{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}";
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
  }
}
