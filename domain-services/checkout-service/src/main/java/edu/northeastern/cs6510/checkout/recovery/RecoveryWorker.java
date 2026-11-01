package edu.northeastern.cs6510.checkout.recovery;

import edu.northeastern.cs6510.checkout.completion.AttemptState;
import edu.northeastern.cs6510.checkout.completion.CompletionAttempt;
import edu.northeastern.cs6510.checkout.completion.CompletionAttemptRepository;
import edu.northeastern.cs6510.checkout.completion.CompletionSaga;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The resend sweep (005 data-model rules 3–4, FR-013, FR-018). An attempt whose reply has not moved
 * it on by its {@code next_retry_at} gets its current state's command written again, with backoff;
 * the claim is a conditional update, so two sweeps (or replicas) never both resend. A RESERVING
 * attempt older than {@code cancel-after} is cancelled instead; a COMMITTING one only ever has its
 * commit resent. After {@code attention-after} sends the attempt is flagged and left to operators.
 * Makes no remote calls and survives restarts: all of its state is in Checkout's database.
 */
@Component
public class RecoveryWorker {
  private static final Logger log = LoggerFactory.getLogger(RecoveryWorker.class);
  private static final int BATCH = 50;

  private final CompletionAttemptRepository attempts;
  private final CompletionSaga saga;
  private final RecoveryProperties recovery;
  private final MeterRegistry meters;
  private final Clock clock;
  private final Map<AttemptState, AtomicLong> byState = new EnumMap<>(AttemptState.class);
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean scanning = new AtomicBoolean(false);

  @Autowired
  public RecoveryWorker(CompletionAttemptRepository attempts, CompletionSaga saga, RecoveryProperties recovery, MeterRegistry meters) {
    this(attempts, saga, recovery, meters, Clock.systemUTC());
  }

  RecoveryWorker(CompletionAttemptRepository attempts, CompletionSaga saga, RecoveryProperties recovery, MeterRegistry meters, Clock clock) {
    this.attempts = attempts;
    this.saga = saga;
    this.recovery = recovery;
    this.meters = meters;
    this.clock = clock;
    for (AttemptState state : AttemptState.values()) {
      AtomicLong count = new AtomicLong();
      byState.put(state, count);
      Gauge.builder("checkout.completion.attempts", count, AtomicLong::get).tag("state", state.name()).register(meters);
    }
  }

  @Scheduled(fixedDelayString = "${checkout.recovery.scan-interval:1s}")
  public void scan() {
    if (!accepting.get() || !scanning.compareAndSet(false, true)) return;
    try {
      attempts.countByState().forEach((state, n) -> byState.get(state).set(n));
      for (CompletionAttempt attempt : attempts.findDueForResend(clock.instant(), BATCH)) {
        if (!accepting.get()) return;
        recover(attempt);
      }
    } catch (RuntimeException e) {
      // A failed sweep (e.g. own database briefly unavailable) is retried on the next tick.
      log.warn("recovery sweep failed: {}", e.toString());
    } finally {
      scanning.set(false);
    }
  }

  void recover(CompletionAttempt attempt) {
    if (attempt.commandSends() >= recovery.attentionAfter()) {
      saga.park(attempt, attempt.commandSends() + " sends of the " + attempt.state() + " command got no reply");
      return;
    }
    String sent = saga.resend(attempt, recovery.cancelAfter(), recovery.backoff(attempt.commandSends()));
    if (sent != null) {
      meters.counter("checkout.recovery.resends", "state", attempt.state().name()).increment();
      log.info("attempt {}: no reply yet, sent {} again (send {})", attempt.attemptId(), sent, attempt.commandSends() + 1);
    }
  }

  /** Stops picking new attempts; an in-flight resend finishes on its own. */
  void stopAccepting() { accepting.set(false); }

  boolean isScanning() { return scanning.get(); }

  /** Due attempts at {@code now}, for tests. */
  Instant now() { return clock.instant(); }
}
