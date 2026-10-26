package edu.northeastern.cs6510.analytics.ingest;

import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Filter 1 of {@code ingest -> window -> rank-and-publish}: records each accepted scan once and gives
 * it the next canonical sequence number. Runs inside the batch's local transaction; the row lock on
 * {@code ingest_state} serializes sequence assignment (fixes finding F2). A duplicate eventId has no
 * effect, so replay and redelivery cannot move any scan's position (FR-022, FR-024).
 */
@Component
public class IngestFilter {
  private final JdbcTemplate db;

  public IngestFilter(JdbcTemplate db) { this.db = db; }

  /** The sequence range this batch added: (previous, current]. Empty when every event was a duplicate. */
  public record Range(long previous, long current) { }

  public Range ingest(List<AcceptedScanV1> events) {
    Long locked = db.queryForObject("SELECT last_sequence FROM ingest_state WHERE id = 1 FOR UPDATE", Long.class);
    long previous = locked == null ? 0 : locked;
    Map<String, AcceptedScanV1> unique = new LinkedHashMap<>();
    for (AcceptedScanV1 event : events) unique.putIfAbsent(event.eventId(), event);   // keeps partition order
    if (unique.isEmpty()) return new Range(previous, previous);

    String marks = unique.keySet().stream().map(id -> "?").collect(Collectors.joining(","));
    Set<String> seen = new HashSet<>(db.queryForList("SELECT event_id FROM ingested_scans WHERE event_id IN (" + marks + ")",
        String.class, unique.keySet().toArray()));
    long sequence = previous;
    Timestamp now = Timestamp.from(Instant.now());
    List<Object[]> rows = new ArrayList<>();
    for (AcceptedScanV1 event : unique.values()) {
      if (seen.contains(event.eventId())) continue;
      sequence++;
      rows.add(new Object[] {event.eventId(), sequence, event.sku(), Timestamp.from(event.acceptedAt()), now});
    }
    if (!rows.isEmpty()) {
      db.batchUpdate("INSERT INTO ingested_scans (event_id, sequence_number, sku, accepted_at, ingested_at) VALUES (?, ?, ?, ?, ?)", rows);
      db.update("UPDATE ingest_state SET last_sequence = ? WHERE id = 1", sequence);
    }
    return new Range(previous, sequence);
  }
}
