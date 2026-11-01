package edu.northeastern.cs6510.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.northeastern.cs6510.contracts.messaging.Envelope;
import edu.northeastern.cs6510.contracts.messaging.MessageHeaders;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EnvelopeTest {
  private static final Envelope REPLY = new Envelope("m-1", "Reserved", 1, "inventory",
      Instant.parse("2026-11-02T15:04:05.123456Z"), "attempt-1", "command-1");

  @Test
  void roundTripsThroughHeaders() {
    assertEquals(REPLY, Envelope.fromHeaders(REPLY.toHeaders()));
  }

  @Test
  void leavesOutUnusedOptionalHeaders() {
    Envelope product = new Envelope("m-2", "ProductChanged", 1, "catalog", Instant.EPOCH, null, null);
    Map<String, byte[]> headers = product.toHeaders();
    assertFalse(headers.containsKey(MessageHeaders.CORRELATION_ID));
    assertFalse(headers.containsKey(MessageHeaders.CAUSATION_ID));
    assertEquals(product, Envelope.fromHeaders(headers));
  }

  @ParameterizedTest
  @ValueSource(strings = {MessageHeaders.MESSAGE_ID, MessageHeaders.MESSAGE_TYPE, MessageHeaders.SCHEMA_VERSION,
      MessageHeaders.PRODUCER, MessageHeaders.OCCURRED_AT})
  void rejectsAMissingRequiredHeader(String header) {
    Map<String, byte[]> headers = new java.util.HashMap<>(REPLY.toHeaders());
    headers.remove(header);
    assertThrows(IllegalArgumentException.class, () -> Envelope.fromHeaders(headers));
  }

  @Test
  void rejectsAMalformedTimestamp() {
    Map<String, byte[]> headers = new java.util.HashMap<>(REPLY.toHeaders());
    headers.put(MessageHeaders.OCCURRED_AT, "yesterday".getBytes());
    assertThrows(IllegalArgumentException.class, () -> Envelope.fromHeaders(headers));
  }
}
