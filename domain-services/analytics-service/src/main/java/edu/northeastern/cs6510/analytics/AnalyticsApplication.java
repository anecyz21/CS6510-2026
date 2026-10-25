package edu.northeastern.cs6510.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The cross-store poller of Checkout's outbox has been removed (private stores, finding F1).
 * Accepted scans arrive through Kafka in US4; until then the popular-items route serves the
 * snapshots already in this service's own tables.
 */
@SpringBootApplication
public class AnalyticsApplication {
    public static void main(String[] args) { SpringApplication.run(AnalyticsApplication.class, args); }
    @RestController static class Popularity { private final JdbcTemplate db; Popularity(JdbcTemplate db){this.db=db;}
        @GetMapping("/analytics/popular-items") Map<String,Object> popular(@RequestParam(defaultValue="10") int limit) {
            List<Map<String,Object>> headers=db.queryForList("SELECT window_start,window_end,computed_at FROM popularity_snapshots ORDER BY window_end DESC LIMIT 1");
            if(headers.isEmpty()) return Map.of("windowSize",1000,"slideInterval",500,"windowStart",0,"windowEnd",0,"computedAt",Instant.now().toString(),"items",List.of());
            Map<String,Object> h=headers.get(0); return Map.of("windowSize",1000,"slideInterval",500,"windowStart",h.get("window_start"),"windowEnd",h.get("window_end"),"computedAt",h.get("computed_at").toString(),"items",db.query("SELECT sku,scan_count,rank_position FROM popularity_snapshot_items WHERE window_end=? ORDER BY rank_position LIMIT ?",(rs,n)->Map.of("sku",rs.getString(1),"name",rs.getString(1),"scanCount",rs.getLong(2),"rank",rs.getInt(3)),h.get("window_end"),Math.max(1,limit)));
        }
    }
}
