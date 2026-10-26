package edu.northeastern.cs6510.analytics.ingest;

import edu.northeastern.cs6510.analytics.rank.RankAndPublishFilter;
import edu.northeastern.cs6510.analytics.window.WindowFilter;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The named pipeline {@code ingest -> window -> rank-and-publish} (constitution III). Each filter has
 * one responsibility and receives only its neighbour's output. The caller runs it inside one local
 * transaction, so a batch's effects and its dedupe and sequence progress commit together (FR-022).
 */
@Component
public class ScanPipeline {
  private final IngestFilter ingest;
  private final WindowFilter window;
  private final RankAndPublishFilter rankAndPublish;

  public ScanPipeline(IngestFilter ingest, WindowFilter window, RankAndPublishFilter rankAndPublish) {
    this.ingest = ingest;
    this.window = window;
    this.rankAndPublish = rankAndPublish;
  }

  public void process(List<AcceptedScanV1> events) {
    rankAndPublish.publish(window.windows(ingest.ingest(events)));
  }
}
