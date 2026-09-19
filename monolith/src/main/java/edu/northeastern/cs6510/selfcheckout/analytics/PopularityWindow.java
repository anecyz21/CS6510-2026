package edu.northeastern.cs6510.selfcheckout.analytics;
import java.time.Instant; import java.util.List;
public record PopularityWindow(int windowSize, int slideInterval, long windowStart, long windowEnd, Instant computedAt, List<PopularItem> items) { }
