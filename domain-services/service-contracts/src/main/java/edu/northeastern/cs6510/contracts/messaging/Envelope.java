package edu.northeastern.cs6510.contracts.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The envelope carried as Kafka headers on every message between domain services (005
 * contracts/README.md). {@code correlationId} and {@code causationId} are null where the contract
 * does not use them. Header values are UTF-8 strings.
 */
public record Envelope(
    String messageId,
    String messageType,
    int schemaVersion,
    String producer,
    Instant occurredAt,
    String correlationId,
    String causationId) {

  /** The headers to attach to a record, in a stable order; null fields are left out. */
  public Map<String, byte[]> toHeaders() {
    Map<String, byte[]> headers = new LinkedHashMap<>();
    put(headers, MessageHeaders.MESSAGE_ID, messageId);
    put(headers, MessageHeaders.MESSAGE_TYPE, messageType);
    put(headers, MessageHeaders.SCHEMA_VERSION, Integer.toString(schemaVersion));
    put(headers, MessageHeaders.PRODUCER, producer);
    put(headers, MessageHeaders.OCCURRED_AT, occurredAt == null ? null : occurredAt.toString());
    put(headers, MessageHeaders.CORRELATION_ID, correlationId);
    put(headers, MessageHeaders.CAUSATION_ID, causationId);
    return headers;
  }

  /**
   * Reads the envelope from record headers.
   *
   * @throws IllegalArgumentException when a required header is missing or malformed, which a
   *     consumer treats as a record that cannot be processed (dead-letter, not retry)
   */
  public static Envelope fromHeaders(Map<String, byte[]> headers) {
    String version = required(headers, MessageHeaders.SCHEMA_VERSION);
    String occurred = required(headers, MessageHeaders.OCCURRED_AT);
    try {
      return new Envelope(
          required(headers, MessageHeaders.MESSAGE_ID),
          required(headers, MessageHeaders.MESSAGE_TYPE),
          Integer.parseInt(version),
          required(headers, MessageHeaders.PRODUCER),
          Instant.parse(occurred),
          optional(headers, MessageHeaders.CORRELATION_ID),
          optional(headers, MessageHeaders.CAUSATION_ID));
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new IllegalArgumentException("malformed envelope header: " + e.getMessage(), e);
    }
  }

  private static void put(Map<String, byte[]> headers, String name, String value) {
    if (value != null) headers.put(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private static String required(Map<String, byte[]> headers, String name) {
    String value = optional(headers, name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing envelope header " + name);
    return value;
  }

  private static String optional(Map<String, byte[]> headers, String name) {
    byte[] raw = headers.get(name);
    return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
  }
}
