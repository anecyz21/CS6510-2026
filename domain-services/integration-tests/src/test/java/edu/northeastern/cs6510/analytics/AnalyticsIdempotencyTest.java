package edu.northeastern.cs6510.analytics;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnalyticsIdempotencyTest {
  @Test void processedScanCheckpointIsUniqueAndUsedByConsumer() throws Exception {
    String migration=Files.readString(Path.of("..", "analytics-service/src/main/resources/db/migration/V1__analytics.sql"));
    String consumer=Files.readString(Path.of("..", "analytics-service/src/main/java/edu/northeastern/cs6510/analytics/AnalyticsApplication.java"));
    assertTrue(migration.contains("processed_scans (scan_id BIGINT PRIMARY KEY"));
    assertTrue(consumer.contains("INSERT IGNORE INTO processed_scans"));
  }
}
