package edu.northeastern.cs6510.analytics.api;

import edu.northeastern.cs6510.analytics.window.WindowFilter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /analytics/popular-items}: the current published window. The pointer and the snapshot
 * are read in one read-only transaction, so a response never mixes two windows (US4-5).
 */
@RestController
public class PopularItemsController {
  private final JdbcTemplate db;

  public PopularItemsController(JdbcTemplate db) { this.db = db; }

  public record PopularItem(String sku, String name, long scanCount, int rank) { }

  @GetMapping("/analytics/popular-items")
  @Transactional(readOnly = true)
  public Map<String, Object> popularItems(@RequestParam(defaultValue = "10") int limit) {
    if (limit < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit must be at least 1.");
    Long end = db.queryForObject("SELECT current_window_end FROM published_snapshot WHERE id = 1", Long.class);
    if (end == null) return response(0, 0, Instant.now(), List.of());
    Map<String, Object> header = db.queryForMap("SELECT window_start, computed_at FROM popularity_snapshots WHERE window_end = ?", end);
    List<PopularItem> items = db.query(
        "SELECT sku, name, scan_count, rank_position FROM popularity_snapshot_items WHERE window_end = ? ORDER BY rank_position LIMIT ?",
        (rs, n) -> new PopularItem(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4)), end, limit);
    return response(((Number) header.get("window_start")).longValue(), end, ((Timestamp) header.get("computed_at")).toInstant(), items);
  }

  private static Map<String, Object> response(long start, long end, Instant computedAt, List<PopularItem> items) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("windowSize", WindowFilter.SIZE);
    body.put("slideInterval", WindowFilter.SLIDE);
    body.put("windowStart", start);
    body.put("windowEnd", end);
    body.put("computedAt", computedAt.toString());
    body.put("items", items);
    return body;
  }
}
