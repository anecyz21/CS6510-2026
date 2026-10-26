package edu.northeastern.cs6510.checkout.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

/** Turns one outbox row into the {@code checkout.accepted-scans.v1} record (key, headers and v1 JSON value). */
@Component
public class AcceptedScanEventMapper {
  private final ObjectMapper json;

  /** {@code acceptedAt} is always ISO-8601 text, whatever the application's global Jackson settings. */
  public AcceptedScanEventMapper(ObjectMapper json) { this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }

  /** One pending outbox row. */
  public record OutboxRow(long outboxId, String eventId, String transactionId, String stationId, String sku, Instant acceptedAt) { }

  public AcceptedScanV1 event(OutboxRow row) {
    return new AcceptedScanV1(AcceptedScanV1.SCHEMA_VERSION, row.eventId(), AcceptedScanV1.EVENT_TYPE, row.transactionId(),
        row.stationId(), row.sku(), row.acceptedAt(), row.outboxId());
  }

  public String toJson(OutboxRow row) {
    try {
      return json.writeValueAsString(event(row));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize outbox row " + row.outboxId(), e);
    }
  }

  /** Key is the transaction id (reserved for future partitioning); headers repeat the identity and version. */
  public ProducerRecord<String, String> record(OutboxRow row) {
    ProducerRecord<String, String> record = new ProducerRecord<>(AcceptedScanV1.TOPIC, row.transactionId(), toJson(row));
    record.headers()
        .add("eventId", row.eventId().getBytes(StandardCharsets.UTF_8))
        .add("eventType", AcceptedScanV1.EVENT_TYPE.getBytes(StandardCharsets.UTF_8))
        .add("schemaVersion", String.valueOf(AcceptedScanV1.SCHEMA_VERSION).getBytes(StandardCharsets.UTF_8));
    return record;
  }
}
