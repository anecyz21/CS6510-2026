package edu.northeastern.cs6510.checkout.recovery;

import edu.northeastern.cs6510.checkout.completion.AttemptDriver;
import edu.northeastern.cs6510.checkout.completion.AttemptState;
import edu.northeastern.cs6510.checkout.completion.CompletionAttempt;
import edu.northeastern.cs6510.checkout.completion.CompletionAttemptRepository;
import edu.northeastern.cs6510.checkout.completion.CompletionProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves completion attempts that a request left unresolved or a crash interrupted (FR-016–FR-018).
 * Each scan leases due attempts and drives them with the same saga steps as a request. An attempt
 * that is still RESERVING after a failure and older than {@code cancel-after} is cancelled; one
 * whose receipt intent is durable (COMMITTING) only ever moves forward. Survives restarts because
 * all of its state is in Checkout's database.
 */
@Component
public class RecoveryWorker {
  private static final Logger log = LoggerFactory.getLogger(RecoveryWorker.class);
  private static final int BATCH = 50;

  private final CompletionAttemptRepository attempts;
  private final AttemptDriver driver;
  private final TransactionOperations transactions;
  private final RecoveryProperties recovery;
  private final CompletionProperties completion;
  private final MeterRegistry meters;
  private final Clock clock;
  private final String owner;
  private final Map<AttemptState, AtomicLong> byState = new EnumMap<>(AttemptState.class);
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean scanning = new AtomicBoolean(false);

  @Autowired
  public RecoveryWorker(CompletionAttemptRepository attempts, AttemptDriver driver, PlatformTransactionManager transactionManager,
                        RecoveryProperties recovery, CompletionProperties completion, MeterRegistry meters) {
    this(attempts, driver, new TransactionTemplate(transactionManager), recovery, completion, meters, Clock.systemUTC(), defaultOwner());
  }

  RecoveryWorker(CompletionAttemptRepository attempts, AttemptDriver driver, TransactionOperations transactions,
                 RecoveryProperties recovery, CompletionProperties completion, MeterRegistry meters, Clock clock, String owner) {
    this.attempts = attempts;
    this.driver = driver;
    this.transactions = transactions;
    this.recovery = recovery;
    this.completion = completion;
    this.meters = meters;
    this.clock = clock;
    this.owner = owner;
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
      for (String attemptId : attempts.findDueForRecovery(clock.instant(), BATCH)) {
        if (!accepting.get()) return;
        recover(attemptId);
      }
    } catch (RuntimeException e) {
      // A failed scan (e.g. own database briefly unavailable) is retried on the next tick.
      log.warn("recovery scan failed: {}", e.toString());
    } finally {
      scanning.set(false);
    }
  }

  private void recover(String attemptId) {
    Instant now = clock.instant();
    if (!Boolean.TRUE.equals(transactions.execute(s -> attempts.acquireLease(attemptId, owner, now.plus(recovery.leaseDuration()), now)))) {
      return;   // another driver (a request or replica) is working on it
    }
    try {
      CompletionAttempt attempt = attempts.find(attemptId).orElseThrow();
      AttemptState step = attempt.state();
      if (step == AttemptState.RESERVING && attempt.stepFailures() > 0
          && attempt.createdAt().isBefore(now.minus(recovery.cancelAfter()))) {
        // Only before the receipt intent is durable may an attempt be abandoned (FR-017).
        transactions.executeWithoutResult(s -> attempts.transition(attemptId, AttemptState.RESERVING, AttemptState.CANCELLING, now));
        step = AttemptState.CANCELLING;
      }
      AttemptDriver.Outcome outcome = driver.drive(attemptId, now.plus(completion.requestDeadline()), owner);
      meters.counter("checkout.recovery.steps", "step", step.name(), "result", outcome.name()).increment();
      CompletionAttempt after = attempts.find(attemptId).orElseThrow();
      if (!after.state().isTerminal() && !after.needsAttention() && after.stepFailures() >= recovery.attentionAfter()) {
        Instant flaggedAt = clock.instant();
        transactions.executeWithoutResult(s -> attempts.markNeedsAttention(attemptId,
            after.stepFailures() + " consecutive failed steps, still retrying: " + after.lastError(), flaggedAt));
      }
    } finally {
      transactions.executeWithoutResult(s -> attempts.releaseLease(attemptId, owner));
    }
  }

  /** Stops picking new attempts; in-flight steps finish on their own. */
  void stopAccepting() { accepting.set(false); }

  boolean isScanning() { return scanning.get(); }

  String owner() { return owner; }

  private static String defaultOwner() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      host = "checkout";
    }
    return host + "-" + UUID.randomUUID();
  }
}
