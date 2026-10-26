package edu.northeastern.cs6510.analytics.window;

import edu.northeastern.cs6510.analytics.ingest.IngestFilter.Range;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Filter 2 of {@code ingest -> window -> rank-and-publish}: turns newly assigned sequence numbers into
 * the hopping windows they complete. Windows hold the latest {@value #SIZE} scans and are published
 * every {@value #SLIDE} scans; the first publication happens at sequence 1 (the preserved startup
 * partial window), so the window ending at {@code e} is {@code [max(1, e - 999), e]} (FR-023).
 */
@Component
public class WindowFilter {
  public static final int SIZE = 1000;
  public static final int SLIDE = 500;

  public record Window(long start, long end) { }

  /** Every publication boundary crossed by the range, oldest first. */
  public List<Window> windows(Range range) {
    List<Window> out = new ArrayList<>();
    if (range.previous() < 1 && range.current() >= 1) out.add(window(1));
    for (long end = (range.previous() / SLIDE + 1) * SLIDE; end <= range.current(); end += SLIDE) out.add(window(end));
    return out;
  }

  public static Window window(long end) { return new Window(Math.max(1, end - (SIZE - 1)), end); }
}
