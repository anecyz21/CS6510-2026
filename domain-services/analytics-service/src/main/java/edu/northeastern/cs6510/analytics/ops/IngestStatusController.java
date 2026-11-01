package edu.northeastern.cs6510.analytics.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import edu.northeastern.cs6510.analytics.catalog.CatalogNameProjection;
import edu.northeastern.cs6510.analytics.messaging.DeadLetterPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/v1/ops/ingest-status}: read-only progress of the ingest pipeline, used by
 * reconciliation check R-6 ({@code outboxTotal == ingestedCount} once lag is zero).
 */
@RestController
public class IngestStatusController {
  private final JdbcTemplate db;
  private final ConsumerLag lag;
  private final DeadLetterPublisher deadLetters;
  private final CatalogNameProjection projection;

  public IngestStatusController(JdbcTemplate db, ConsumerLag lag, DeadLetterPublisher deadLetters, CatalogNameProjection projection) {
    this.db = db;
    this.lag = lag;
    this.deadLetters = deadLetters;
    this.projection = projection;
  }

  @GetMapping("/internal/v1/ops/ingest-status")
  public Map<String, Object> status() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ingestedCount", db.queryForObject("SELECT COUNT(*) FROM ingested_scans", Long.class));
    body.put("lastSequence", db.queryForObject("SELECT last_sequence FROM ingest_state WHERE id = 1", Long.class));
    body.put("currentWindowEnd", db.queryForObject("SELECT current_window_end FROM published_snapshot WHERE id = 1", Long.class));
    body.put("consumerLag", lag.lag());
    body.put("rejected", db.queryForObject("SELECT COUNT(*) FROM ingest_rejections", Long.class));
    // 005 reconciliation additions (data-model): dead letters and the name copy version.
    body.put("deadLetterRecords", deadLetters.topicRecords());
    body.put("projectionCatalogVersion", projection.catalogVersion());
    return body;
  }
}
