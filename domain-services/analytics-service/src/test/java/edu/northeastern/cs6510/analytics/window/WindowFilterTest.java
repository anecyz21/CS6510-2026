package edu.northeastern.cs6510.analytics.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.analytics.ingest.IngestFilter.Range;
import edu.northeastern.cs6510.analytics.rank.RankAndPublishFilter;
import edu.northeastern.cs6510.analytics.rank.RankAndPublishFilter.Ranked;
import edu.northeastern.cs6510.analytics.window.WindowFilter.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hopping-window boundaries and deterministic ranking (T090, FR-023). */
class WindowFilterTest {
  private final WindowFilter filter = new WindowFilter();

  @Test
  void boundariesAreCrossedAtOneThenEveryFiveHundred() {
    List<Long> ends = new ArrayList<>();
    for (long seq = 1; seq <= 1500; seq++) filter.windows(new Range(seq - 1, seq)).forEach(w -> ends.add(w.end()));
    assertEquals(List.of(1L, 500L, 1000L, 1500L), ends);
  }

  @Test
  void windowEndingAtEHoldsTheLatestThousandScans() {
    assertEquals(new Window(1, 1), WindowFilter.window(1));
    assertEquals(new Window(1, 500), WindowFilter.window(500));
    assertEquals(new Window(1, 1000), WindowFilter.window(1000));
    assertEquals(new Window(501, 1500), WindowFilter.window(1500));
  }

  @Test
  void oneBatchCrossingTwoBoundariesPublishesBothOldestFirst() {
    assertEquals(List.of(new Window(1, 1), new Window(1, 500)), filter.windows(new Range(0, 600)));
    assertEquals(List.of(new Window(1, 1000), new Window(501, 1500)), filter.windows(new Range(999, 1500)));
  }

  @Test
  void aBatchThatCrossesNoBoundaryOrAddsNothingPublishesNothing() {
    assertTrue(filter.windows(new Range(501, 999)).isEmpty());
    assertTrue(filter.windows(new Range(1000, 1000)).isEmpty(), "an all-duplicate batch adds no sequence numbers");
  }

  @Test
  void rankingIsByCountDescendingThenSkuAscending() {
    List<Ranked> ranked = RankAndPublishFilter.rank(Map.of("SKU-000003", 4L, "SKU-000001", 2L, "SKU-000002", 4L, "SKU-000004", 1L));
    assertEquals(List.of(
        new Ranked("SKU-000002", 4, 1),
        new Ranked("SKU-000003", 4, 2),
        new Ranked("SKU-000001", 2, 3),
        new Ranked("SKU-000004", 1, 4)), ranked);
  }

  @Test
  void aScanContributesOnceToEveryWindowContainingIt() {
    long scan = 750;
    List<Long> containing = new ArrayList<>();
    for (long seq = 1; seq <= 2000; seq++) {
      for (Window w : filter.windows(new Range(seq - 1, seq))) {
        if (w.start() <= scan && scan <= w.end()) containing.add(w.end());
      }
    }
    assertEquals(List.of(1000L, 1500L), containing);
  }
}
