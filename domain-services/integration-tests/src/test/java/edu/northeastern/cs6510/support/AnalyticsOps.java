package edu.northeastern.cs6510.support;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepted-scan flow checks for US4: Analytics' ingest status, Checkout's outbox counts (both from
 * their operator endpoints) and read-only queries of Analytics' own store with its owner credentials.
 */
public final class AnalyticsOps {
  private static final PublicApi CHECKOUT = new PublicApi(Stack.SERVICE_URLS.get("checkout"));
  private static final PublicApi ANALYTICS = new PublicApi(Stack.SERVICE_URLS.get("analytics"));

  private AnalyticsOps() { }

  public record Status(long ingestedCount, long lastSequence, Long currentWindowEnd, Long consumerLag, long rejected) { }

  public record Outbox(long pending, long total) { }

  public static Status ingestStatus() {
    JsonNode body = ANALYTICS.get("/internal/v1/ops/ingest-status").body();
    return new Status(body.get("ingestedCount").asLong(), body.get("lastSequence").asLong(), nullableLong(body, "currentWindowEnd"),
        nullableLong(body, "consumerLag"), body.get("rejected").asLong());
  }

  public static Outbox outbox() {
    JsonNode body = CHECKOUT.get("/internal/v1/ops/reconciliation-export").body();
    return new Outbox(body.get("outboxPending").asLong(), body.get("outboxTotal").asLong());
  }

  /** Waits until Checkout's outbox is drained and Analytics' consumer has no lag; returns the settled status. */
  public static Status awaitDrained(Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    String last = "";
    while (Instant.now().isBefore(deadline)) {
      try {
        Outbox outbox = outbox();
        Status status = ingestStatus();
        last = outbox + " " + status;
        if (outbox.pending() == 0 && status.consumerLag() != null && status.consumerLag() == 0) return status;
      } catch (RuntimeException e) {
        last = e.getMessage();
      }
      CheckoutDb.sleep(500);
    }
    fail("accepted scans not drained within " + timeout + ": " + last);
    return null;
  }

  /** A published snapshot row of Analytics' own store. */
  public record SnapshotItem(long windowEnd, String sku, String name, long scanCount, int rank) { }

  public static List<SnapshotItem> snapshotItems(long windowEnd) {
    return query("SELECT window_end, sku, name, scan_count, rank_position FROM popularity_snapshot_items WHERE window_end = ? ORDER BY rank_position",
        rs -> new SnapshotItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5)), windowEnd);
  }

  public static List<SnapshotItem> allSnapshotItems() {
    return query("SELECT window_end, sku, name, scan_count, rank_position FROM popularity_snapshot_items ORDER BY window_end, rank_position",
        rs -> new SnapshotItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5)));
  }

  /** (window_start, window_end) of every stored snapshot, oldest first. */
  public static List<long[]> snapshotWindows() {
    return query("SELECT window_start, window_end FROM popularity_snapshots ORDER BY window_end",
        rs -> new long[] {rs.getLong(1), rs.getLong(2)});
  }

  /** event_id and sequence_number of every ingested scan, by sequence. */
  public static List<String> ingestedSequence() {
    return query("SELECT CONCAT(sequence_number, ':', event_id, ':', sku) FROM ingested_scans ORDER BY sequence_number",
        rs -> rs.getString(1));
  }

  public interface Row<T> { T map(ResultSet rs) throws SQLException; }

  public static <T> List<T> query(String sql, Row<T> row, Object... parameters) {
    try (Connection c = Stack.ownerConnection("analytics"); PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) s.setObject(i + 1, parameters[i]);
      List<T> out = new ArrayList<>();
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) out.add(row.map(rs));
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Long nullableLong(JsonNode body, String field) {
    return body.hasNonNull(field) ? body.get(field).asLong() : null;
  }
}
