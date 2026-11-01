package edu.northeastern.cs6510.checkout.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend and recovery budgets (005 research R5, data-model rules 3–4). Every value is configurable
 * and recorded with each measured run.
 *
 * @param scanInterval   delay between recovery sweeps
 * @param cancelAfter    an attempt still RESERVING this long after it was created is cancelled
 * @param attentionAfter sends of one state's command before the attempt is flagged for operators
 * @param backoffBase    resend backoff: base x 2^(sends - 1) ...
 * @param backoffCap     ... capped at this
 */
@ConfigurationProperties("checkout.recovery")
public record RecoveryProperties(
    Duration scanInterval,
    Duration cancelAfter,
    Integer attentionAfter,
    Duration backoffBase,
    Duration backoffCap) {
  public RecoveryProperties {
    if (scanInterval == null) scanInterval = Duration.ofSeconds(1);
    if (cancelAfter == null) cancelAfter = Duration.ofSeconds(30);
    if (attentionAfter == null) attentionAfter = 20;
    if (backoffBase == null) backoffBase = Duration.ofMillis(250);
    if (backoffCap == null) backoffCap = Duration.ofSeconds(5);
  }

  public static RecoveryProperties defaults() { return new RecoveryProperties(null, null, null, null, null); }

  /** Delay before the next resend after {@code sends} sends of the current command: base x 2^(sends - 1), capped. */
  public Duration backoff(int sends) {
    long millis = backoffBase.toMillis() << Math.min(Math.max(sends - 1, 0), 20);
    return Duration.ofMillis(Math.min(millis, backoffCap.toMillis()));
  }
}
