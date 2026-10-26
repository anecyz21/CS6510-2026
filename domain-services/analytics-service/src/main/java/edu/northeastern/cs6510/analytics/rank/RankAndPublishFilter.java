package edu.northeastern.cs6510.analytics.rank;

import edu.northeastern.cs6510.analytics.catalog.CatalogNameProjection;
import edu.northeastern.cs6510.analytics.window.WindowFilter;
import edu.northeastern.cs6510.analytics.window.WindowFilter.Window;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Filter 3 of {@code ingest -> window -> rank-and-publish}: ranks each completed window and publishes
 * it. The snapshot rows are written under the new window end, and only then does the pointer move,
 * all in the batch's transaction, so readers see a complete old or new ranking (US4-5, finding F3).
 */
@Component
public class RankAndPublishFilter {
  /** Snapshots older than this many slides behind the newest are pruned. */
  private static final int KEEP_SLIDES = 10;

  private final JdbcTemplate db;
  private final CatalogNameProjection names;

  public RankAndPublishFilter(JdbcTemplate db, CatalogNameProjection names) {
    this.db = db;
    this.names = names;
  }

  public record Ranked(String sku, long scanCount, int rank) { }

  /** Thrown when a ranked SKU has no projected name: the batch rolls back and is redelivered later. */
  public static class MissingNameException extends RuntimeException {
    public MissingNameException(String sku) { super("no catalog name for " + sku + "; waiting for the name projection"); }
  }

  public void publish(List<Window> windows) {
    for (Window window : windows) publish(window);
  }

  private void publish(Window window) {
    Map<String, Long> counts = new TreeMap<>();
    db.query("SELECT sku, COUNT(*) FROM ingested_scans WHERE sequence_number BETWEEN ? AND ? GROUP BY sku",
        rs -> { counts.put(rs.getString(1), rs.getLong(2)); }, window.start(), window.end());
    List<Ranked> ranked = rank(counts);
    List<Object[]> items = new ArrayList<>(ranked.size());
    for (Ranked r : ranked) {
      String name = names.name(r.sku()).orElseThrow(() -> new MissingNameException(r.sku()));
      items.add(new Object[] {window.end(), r.sku(), name, r.scanCount(), r.rank()});
    }
    db.update("INSERT INTO popularity_snapshots (window_end, window_start, computed_at) VALUES (?, ?, ?)",
        window.end(), window.start(), Timestamp.from(Instant.now()));
    db.batchUpdate("INSERT INTO popularity_snapshot_items (window_end, sku, name, scan_count, rank_position) VALUES (?, ?, ?, ?, ?)", items);
    db.update("UPDATE published_snapshot SET current_window_end = ? WHERE id = 1 AND (current_window_end IS NULL OR current_window_end < ?)",
        window.end(), window.end());
    long oldestKept = window.end() - (long) KEEP_SLIDES * WindowFilter.SLIDE;
    db.update("DELETE FROM popularity_snapshot_items WHERE window_end < ?", oldestKept);
    db.update("DELETE FROM popularity_snapshots WHERE window_end < ?", oldestKept);
  }

  /** Deterministic ranking: scan count descending, then SKU ascending; ranks start at 1. */
  public static List<Ranked> rank(Map<String, Long> counts) {
    List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
    entries.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
    List<Ranked> out = new ArrayList<>(entries.size());
    for (int i = 0; i < entries.size(); i++) out.add(new Ranked(entries.get(i).getKey(), entries.get(i).getValue(), i + 1));
    return out;
  }
}
