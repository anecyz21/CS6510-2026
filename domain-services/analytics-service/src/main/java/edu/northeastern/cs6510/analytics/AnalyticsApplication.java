package edu.northeastern.cs6510.analytics;

import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Accepted scans arrive from Checkout through Kafka and flow through the named pipeline
 * {@code ingest -> window -> rank-and-publish}; the cross-store poller is gone (finding F1).
 */
@SpringBootApplication
@EnableScheduling
public class AnalyticsApplication {
    public static void main(String[] args) {
        // Catalog and Kafka restart independently: never remember a failed lookup.
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        Security.setProperty("networkaddress.cache.ttl", "5");
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
