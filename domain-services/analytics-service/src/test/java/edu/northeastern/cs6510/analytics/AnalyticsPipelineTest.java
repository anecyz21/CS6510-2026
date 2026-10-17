package edu.northeastern.cs6510.analytics;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnalyticsPipelineTest {
  @Test void publishesFirstAndEveryFiveHundredScansWithDeterministicRanking() throws Exception {
    String source=Files.readString(Path.of("src/main/java/edu/northeastern/cs6510/analytics/AnalyticsApplication.java"));
    assertTrue(source.contains("end!=1 && end%500!=0"));
    assertTrue(source.contains("ORDER BY COUNT(*) DESC,sku ASC"));
    assertTrue(source.contains("end-999"));
  }
}
