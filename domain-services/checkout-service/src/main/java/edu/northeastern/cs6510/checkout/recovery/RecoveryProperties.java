package edu.northeastern.cs6510.checkout.recovery;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry, lease and recovery budgets (research R9). Every value is configurable and recorded with
 * each measured run.
 *
 * @param scanInterval     delay between recovery scans
 * @param cancelAfter      an attempt still RESERVING after a failure and older than this is cancelled
 * @param attentionAfter   consecutive failed steps before an attempt is flagged for operators
 * @param leaseDuration    single-driver lease on an attempt
 * @param backoffBase      recovery backoff: base x 2^failures ...
 * @param backoffCap       ... capped at this
 * @param inRequestRetries extra tries of one Inventory command inside a public request
 * @param inRequestBackoff pauses between those tries
 */
@ConfigurationProperties("checkout.recovery")
public record RecoveryProperties(
    Duration scanInterval,
    Duration cancelAfter,
    Integer attentionAfter,
    Duration leaseDuration,
    Duration backoffBase,
    Duration backoffCap,
    Integer inRequestRetries,
    List<Duration> inRequestBackoff) {

  public RecoveryProperties {
    if (scanInterval == null) scanInterval = Duration.ofSeconds(1);
    if (cancelAfter == null) cancelAfter = Duration.ofSeconds(30);
    if (attentionAfter == null) attentionAfter = 20;
    if (leaseDuration == null) leaseDuration = Duration.ofSeconds(10);
    if (backoffBase == null) backoffBase = Duration.ofMillis(250);
    if (backoffCap == null) backoffCap = Duration.ofSeconds(5);
    if (inRequestRetries == null) inRequestRetries = 3;
    if (inRequestBackoff == null || inRequestBackoff.isEmpty()) {
      inRequestBackoff = List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));
    }
  }

  public static RecoveryProperties defaults() { return new RecoveryProperties(null, null, null, null, null, null, null, null); }

  /** Delay before the next recovery try after {@code failures} earlier consecutive failures: base x 2^failures, capped. */
  public Duration backoff(int failures) {
    long millis = backoffBase.toMillis() << Math.min(Math.max(failures, 0), 20);
    return Duration.ofMillis(Math.min(millis, backoffCap.toMillis()));
  }

  /** Pause before in-request retry number {@code retry} (0-based). */
  public Duration retryPause(int retry) {
    return inRequestBackoff.get(Math.min(retry, inRequestBackoff.size() - 1));
  }
}
