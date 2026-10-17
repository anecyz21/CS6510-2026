package edu.northeastern.cs6510.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import jakarta.annotation.PreDestroy;

@SpringBootApplication
@EnableScheduling
public class AnalyticsApplication {
    public static void main(String[] args) { SpringApplication.run(AnalyticsApplication.class, args); }
    @RestController static class Readiness { private final JdbcTemplate db; Readiness(JdbcTemplate db){this.db=db;} @GetMapping("/actuator/health") String health() { db.queryForObject("SELECT 1", Integer.class); return "{\"status\":\"UP\"}"; } }
    /** Database-only ingest → window → rank-and-publish pipeline.  Duplicate scan ids are checkpoints, not contributions. */
    @org.springframework.stereotype.Component static class OutboxConsumer {
        private final JdbcTemplate db; OutboxConsumer(JdbcTemplate db){this.db=db;}
        @Scheduled(fixedDelayString="${analytics.poll-ms:1000}") void ingestWindowRankAndPublish() {
            List<Map<String,Object>> scans=db.queryForList("SELECT scan_id, sku, accepted_at FROM checkout.accepted_scan_outbox ORDER BY scan_id LIMIT 500");
            for (Map<String,Object> s: scans) {
                Long id=((Number)s.get("scan_id")).longValue();
                if (db.update("INSERT IGNORE INTO processed_scans(scan_id) VALUES(?)", id)==1)
                    db.update("INSERT INTO analytics_events(sequence_number,scan_id,sku,accepted_at) SELECT COALESCE(MAX(sequence_number),0)+1,?,?,? FROM analytics_events", id,s.get("sku"),s.get("accepted_at"));
            }
            Long end=db.queryForObject("SELECT MAX(sequence_number) FROM analytics_events",Long.class); if(end==null || (end!=1 && end%500!=0)) return;
            long start=Math.max(1,end-999); db.update("INSERT INTO popularity_snapshots(window_end,window_start) VALUES(?,?) ON DUPLICATE KEY UPDATE window_start=VALUES(window_start)",end,start);
            db.update("DELETE FROM popularity_snapshot_items WHERE window_end=?",end);
            db.update("INSERT INTO popularity_snapshot_items(window_end,sku,scan_count,rank_position) SELECT ?,sku,COUNT(*),ROW_NUMBER() OVER(ORDER BY COUNT(*) DESC,sku ASC) FROM analytics_events WHERE sequence_number BETWEEN ? AND ? GROUP BY sku",end,start,end);
        }
        /** One last idempotent poll makes graceful shutdown/restart leave durable work eligible for the next process. */
        @PreDestroy void drainBeforeStop() { ingestWindowRankAndPublish(); }
    }
    @RestController static class Popularity { private final JdbcTemplate db; Popularity(JdbcTemplate db){this.db=db;}
        @GetMapping("/analytics/popular-items") Map<String,Object> popular(@RequestParam(defaultValue="10") int limit) {
            List<Map<String,Object>> headers=db.queryForList("SELECT window_start,window_end,computed_at FROM popularity_snapshots ORDER BY window_end DESC LIMIT 1");
            if(headers.isEmpty()) return Map.of("windowSize",1000,"slideInterval",500,"windowStart",0,"windowEnd",0,"computedAt",Instant.now().toString(),"items",List.of());
            Map<String,Object> h=headers.get(0); return Map.of("windowSize",1000,"slideInterval",500,"windowStart",h.get("window_start"),"windowEnd",h.get("window_end"),"computedAt",h.get("computed_at").toString(),"items",db.query("SELECT sku,scan_count,rank_position FROM published_popularity WHERE window_end=? ORDER BY rank_position LIMIT ?",(rs,n)->Map.of("sku",rs.getString(1),"name",rs.getString(1),"scanCount",rs.getLong(2),"rank",rs.getInt(3)),h.get("window_end"),Math.max(1,limit)));
        }
    }
}
