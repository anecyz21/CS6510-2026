package edu.northeastern.cs6510.checkout.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.checkout.outbox.AcceptedScanEventMapper.OutboxRow;
import edu.northeastern.cs6510.contracts.Fixtures;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Producer side of the accepted-scan fixture (T099): the relay's record equals the fixture field by field. */
class AcceptedScanSerializationTest {
  private final AcceptedScanEventMapper mapper = new AcceptedScanEventMapper(Jackson2ObjectMapperBuilder.json().build());
  private final OutboxRow row = new OutboxRow(18342, "0192c3f4-8b7a-7cc1-9a51-2f0e5d6c1a90",
      "7b1e2c94-5d3a-4f6e-9b21-0c8d7e6f5a43", "station-17", "SKU-000001", Instant.parse("2026-10-24T12:00:00.123456Z"));

  @Test
  void valueEqualsTheFixtureFieldByField() throws Exception {
    JsonNode fixture = Fixtures.read("accepted-scan-v1.json");
    JsonNode actual = Fixtures.JSON.readTree(mapper.toJson(row));
    assertEquals(fixture.size(), actual.size(), "no extra or missing fields: " + actual);
    fixture.fieldNames().forEachRemaining(field -> {
      if (field.equals("acceptedAt")) {
        assertEquals(Instant.parse(fixture.get(field).asText()), Instant.parse(actual.get(field).asText()), field);
      } else {
        assertEquals(fixture.get(field), actual.get(field), field);
      }
    });
  }

  @Test
  void keyAndHeadersCarryTheIdentity() {
    ProducerRecord<String, String> record = mapper.record(row);
    assertEquals(AcceptedScanV1.TOPIC, record.topic());
    assertEquals(row.transactionId(), record.key());
    assertEquals(row.eventId(), header(record, "eventId"));
    assertEquals("AcceptedScan", header(record, "eventType"));
    assertEquals("1", header(record, "schemaVersion"));
  }

  @Test
  void carriesTheSharedEnvelope() {
    ProducerRecord<String, String> record = mapper.record(row);
    assertEquals(row.eventId(), header(record, "messageId"));
    assertEquals("AcceptedScan", header(record, "messageType"));
    assertEquals("checkout", header(record, "producer"));
    assertEquals(row.acceptedAt(), Instant.parse(header(record, "occurredAt")));
    Map<String, byte[]> headers = new HashMap<>();
    record.headers().forEach(h -> headers.put(h.key(), h.value()));
    assertEquals(row.eventId(), Envelope.fromHeaders(headers).messageId());
  }

  private static String header(ProducerRecord<String, String> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }
}
