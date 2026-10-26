package edu.northeastern.cs6510.analytics.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes accepted scans in batches. Valid events go through {@link ScanPipeline}; unreadable ones
 * and newer schema versions are recorded in {@code ingest_rejections}. Both happen in one local
 * transaction, and the offsets are acknowledged only after it commits. A failure is reported from the
 * first record, so the error handler seeks back and the same batch is redelivered.
 */
@Component
public class AcceptedScanListener {
  private static final Logger log = LoggerFactory.getLogger(AcceptedScanListener.class);

  private final ScanPipeline pipeline;
  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final Counter rejected;

  public AcceptedScanListener(ScanPipeline pipeline, JdbcTemplate db, PlatformTransactionManager transactionManager,
                              ObjectMapper json, MeterRegistry meters) {
    this.pipeline = pipeline;
    this.db = db;
    this.transactions = new TransactionTemplate(transactionManager);
    this.json = json;
    this.rejected = meters.counter("analytics.ingest.rejected");
  }

  private record Rejection(long offset, String reason, String raw) { }

  // groupId must be explicit: otherwise Spring Kafka uses the listener id as the consumer group.
  @KafkaListener(id = "accepted-scan-ingest", groupId = KafkaConsumerConfig.GROUP, topics = AcceptedScanV1.TOPIC,
      containerFactory = "acceptedScanListenerFactory")
  public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
    List<AcceptedScanV1> events = new ArrayList<>(records.size());
    List<Rejection> rejections = new ArrayList<>();
    for (ConsumerRecord<String, String> record : records) {
      String reason;
      AcceptedScanV1 event = null;
      try {
        event = parse(record.value());
        reason = invalid(event);
      } catch (Exception e) {
        reason = "unparseable: " + e.getClass().getSimpleName();
      }
      if (reason == null) events.add(event);
      else rejections.add(new Rejection(record.offset(), reason, record.value()));
    }
    try {
      transactions.executeWithoutResult(status -> {
        Timestamp now = Timestamp.from(Instant.now());
        for (Rejection r : rejections) {
          db.update("INSERT IGNORE INTO ingest_rejections (kafka_offset, reason, raw_value, rejected_at) VALUES (?, ?, ?, ?)",
              r.offset(), r.reason(), r.raw(), now);
        }
        pipeline.process(events);
      });
    } catch (RuntimeException e) {
      log.warn("accepted-scan batch of {} failed and will be redelivered: {}", records.size(), e.getMessage());
      throw new BatchListenerFailedException("accepted-scan batch failed", e, 0);
    }
    ack.acknowledge();
    if (!rejections.isEmpty()) {
      rejected.increment(rejections.size());
      log.warn("rejected {} accepted-scan records (first at offset {}: {})", rejections.size(),
          rejections.get(0).offset(), rejections.get(0).reason());
    }
  }

  /** Parses a v1 value; unknown fields are ignored (contract: additive changes stay v1). */
  public AcceptedScanV1 parse(String value) throws java.io.IOException {
    return json.readValue(value, AcceptedScanV1.class);
  }

  /** Why a parsed event cannot be ingested, or null when it can. */
  public static String invalid(AcceptedScanV1 event) {
    if (event == null) return "empty value";
    if (event.schemaVersion() > AcceptedScanV1.SCHEMA_VERSION) return "unsupported schemaVersion " + event.schemaVersion();
    if (event.schemaVersion() < 1) return "missing schemaVersion";
    if (event.eventId() == null || event.sku() == null || event.acceptedAt() == null) return "missing required field";
    return null;
  }
}
