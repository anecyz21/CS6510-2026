package edu.northeastern.cs6510.checkout.recovery;

import edu.northeastern.cs6510.checkout.completion.CompletionAttemptRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Graceful shutdown for recovery (research R17): stop picking attempts, let the in-flight scan
 * finish (every step ends in a durable state), then hand this process's leases back so another
 * replica or the restarted process can continue at once instead of waiting for them to expire.
 */
@Component
public class RecoveryLifecycle implements SmartLifecycle {
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(15);
  private final RecoveryWorker worker;
  private final CompletionAttemptRepository attempts;
  private volatile boolean running;

  public RecoveryLifecycle(RecoveryWorker worker, CompletionAttemptRepository attempts) {
    this.worker = worker;
    this.attempts = attempts;
  }

  @Override
  public void start() { running = true; }

  @Override
  public void stop() {
    worker.stopAccepting();
    Instant giveUp = Instant.now().plus(DRAIN_TIMEOUT);
    while (worker.isScanning() && Instant.now().isBefore(giveUp)) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    attempts.releaseLeasesOwnedBy(worker.owner());
    running = false;
  }

  @Override
  public boolean isRunning() { return running; }

  /** Stop before the web server and data source are shut down (higher phases stop first). */
  @Override
  public int getPhase() { return Integer.MAX_VALUE - 1000; }
}
