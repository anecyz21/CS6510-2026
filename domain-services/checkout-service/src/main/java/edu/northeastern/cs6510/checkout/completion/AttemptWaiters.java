package edu.northeastern.cs6510.checkout.completion;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Lets a public completion request wait for its attempt to settle without polling the database
 * every few milliseconds (005 research R5). The reply listener completes the attempt's future right
 * after the transaction that made it terminal commits. A request also re-reads the attempt every
 * {@value #FALLBACK_MILLIS} ms, which covers a reply applied by another Checkout replica and an
 * attempt that settled before the request started waiting. At the deadline the outcome is
 * UNRESOLVED and the attempt keeps settling in the background.
 */
@Component
public class AttemptWaiters {
  static final long FALLBACK_MILLIS = 250;

  /** How an attempt ended, as far as a waiting request knows. UNRESOLVED: still in progress at the deadline. */
  public enum Outcome { FINALIZED, REJECTED, CANCELLED, UNRESOLVED }

  /** The outcome and, when the listener had it in memory, the terminal attempt (finalized lines and time). */
  public record Result(Outcome outcome, CompletionAttempt attempt) { }

  private final CompletionAttemptRepository attempts;
  private final Map<String, CompletableFuture<Result>> waiting = new ConcurrentHashMap<>();

  public AttemptWaiters(CompletionAttemptRepository attempts) { this.attempts = attempts; }

  /**
   * Waits until the attempt is terminal or the deadline passes. Joined requests share one future.
   * The database is read only when no reply arrived within {@value #FALLBACK_MILLIS} ms.
   */
  public Result await(String attemptId, Instant deadline) {
    CompletableFuture<Result> future = waiting.computeIfAbsent(attemptId, id -> new CompletableFuture<>());
    try {
      while (true) {
        long left = Duration.between(Instant.now(), deadline).toMillis();
        if (left <= 0) return new Result(Outcome.UNRESOLVED, null);
        try {
          return future.get(Math.min(left, FALLBACK_MILLIS), TimeUnit.MILLISECONDS);
        } catch (TimeoutException noReplyYet) {
          Result stored = terminal(attemptId);
          if (stored != null) return stored;
        } catch (ExecutionException e) {
          throw new IllegalStateException(e.getCause());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(Outcome.UNRESOLVED, null);
    } finally {
      waiting.remove(attemptId, future);
    }
  }

  /** Called after the transaction that made {@code attempt} terminal has committed. */
  public void settled(CompletionAttempt attempt) {
    Outcome outcome = outcome(attempt.state());
    CompletableFuture<Result> future = waiting.get(attempt.attemptId());
    if (outcome != null && future != null) future.complete(new Result(outcome, attempt));
  }

  /** Requests currently waiting, for tests and metrics. */
  int waitingCount() { return waiting.size(); }

  private Result terminal(String attemptId) {
    return attempts.find(attemptId)
        .map(a -> {
          Outcome outcome = outcome(a.state());
          return outcome == null ? null : new Result(outcome, a);
        })
        .orElse(null);
  }

  static Outcome outcome(AttemptState state) {
    return switch (state) {
      case FINALIZED -> Outcome.FINALIZED;
      case REJECTED -> Outcome.REJECTED;
      case CANCELLED -> Outcome.CANCELLED;
      default -> null;
    };
  }
}
