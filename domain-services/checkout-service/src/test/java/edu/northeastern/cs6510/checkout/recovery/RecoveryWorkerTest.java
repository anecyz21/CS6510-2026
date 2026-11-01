package edu.northeastern.cs6510.checkout.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.checkout.completion.AttemptLine;
import edu.northeastern.cs6510.checkout.completion.AttemptState;
import edu.northeastern.cs6510.checkout.completion.AttemptWaiters;
import edu.northeastern.cs6510.checkout.completion.CompletionSaga;
import edu.northeastern.cs6510.checkout.completion.FlagTransactions;
import edu.northeastern.cs6510.checkout.completion.InMemoryAttemptRepository;
import edu.northeastern.cs6510.checkout.completion.MutableClock;
import edu.northeastern.cs6510.checkout.completion.RecordingCommands;
import edu.northeastern.cs6510.checkout.fault.FaultInjector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The resend sweep against fakes and a controllable clock (005 T044, data-model rules 3–4). */
class RecoveryWorkerTest {
  private static final Instant T0 = Instant.parse("2026-11-02T12:00:00Z");
  private static final AttemptLine LINE = new AttemptLine("SKU-A", 1, 250, "Apple", null);

  private MutableClock clock;
  private InMemoryAttemptRepository attempts;
  private RecordingCommands commands;
  private RecoveryWorker worker;
  private RecoveryProperties recovery;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    FlagTransactions transactions = new FlagTransactions();
    attempts = new InMemoryAttemptRepository(transactions::inside, clock);
    commands = new RecordingCommands(transactions::inside);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CompletionSaga saga = new CompletionSaga(attempts, commands, new AttemptWaiters(attempts), transactions, FaultInjector.NONE, meters, clock);
    recovery = RecoveryProperties.defaults();
    worker = new RecoveryWorker(attempts, saga, recovery, meters, clock);
  }

  @Test
  void anAttemptNotYetDueIsLeftAlone() {
    attempts.add("a-1", "tx-1", AttemptState.RESERVING, T0, 1, T0.plusSeconds(1), LINE);
    worker.scan();
    assertEquals(List.of(), commands.written);
  }

  @Test
  void aDueAttemptGetsItsCurrentCommandAgainWithBackoff() {
    attempts.add("a-1", "tx-1", AttemptState.RESERVING, T0, 1, T0.plusSeconds(1), LINE);
    clock.advance(Duration.ofSeconds(1));

    worker.scan();
    assertEquals(List.of("ReserveRequested a-1"), commands.written);
    assertEquals(2, attempts.get("a-1").commandSends());
    assertEquals(clock.instant().plus(Duration.ofMillis(250)), attempts.get("a-1").nextRetryAt(), "250 ms after the first resend");

    worker.scan();
    assertEquals(1, commands.written.size(), "not due again yet");

    clock.advance(Duration.ofMillis(250));
    worker.scan();
    assertEquals(3, attempts.get("a-1").commandSends());
    assertEquals(clock.instant().plus(Duration.ofMillis(500)), attempts.get("a-1").nextRetryAt(), "the backoff doubles");
  }

  @Test
  void backoffIsCappedAtFiveSeconds() {
    assertEquals(Duration.ofMillis(250), recovery.backoff(1));
    assertEquals(Duration.ofMillis(4000), recovery.backoff(5));
    assertEquals(Duration.ofSeconds(5), recovery.backoff(6));
    assertEquals(Duration.ofSeconds(5), recovery.backoff(19));
  }

  @Test
  void twoConcurrentSweepsResendOnce() {
    attempts.add("a-1", "tx-1", AttemptState.COMMITTING, T0, 1, T0, LINE);
    var due = attempts.findDueForResend(T0, 10).get(0);

    worker.recover(due);
    worker.recover(due);   // a second sweep that read the same row

    assertEquals(List.of("CommitRequested a-1"), commands.written);
  }

  @Test
  void reservingPastCancelAfterIsCancelled() {
    attempts.add("a-1", "tx-1", AttemptState.RESERVING, T0, 4, T0.plusSeconds(31), LINE);
    clock.advance(Duration.ofSeconds(31));

    worker.scan();

    assertEquals(AttemptState.CANCELLING, attempts.get("a-1").state());
    assertEquals(List.of("CancelRequested a-1"), commands.written);
  }

  @Test
  void committingIsNeverCancelledOnlyResent() {
    attempts.add("a-1", "tx-1", AttemptState.COMMITTING, T0, 4, T0.plusSeconds(60), LINE);
    clock.advance(Duration.ofSeconds(60));

    worker.scan();

    assertEquals(AttemptState.COMMITTING, attempts.get("a-1").state());
    assertEquals(List.of("CommitRequested a-1"), commands.written);
  }

  @Test
  void afterAttentionAfterSendsTheAttemptIsFlaggedAndNoLongerResent() {
    attempts.add("a-1", "tx-1", AttemptState.COMMITTING, T0, recovery.attentionAfter(), T0, LINE);

    worker.scan();
    assertTrue(attempts.get("a-1").needsAttention());
    assertEquals(List.of(), commands.written);

    clock.advance(Duration.ofMinutes(5));
    worker.scan();
    assertEquals(List.of(), commands.written, "a flagged attempt waits for an operator retry");
  }

  @Test
  void terminalAttemptsAreNeverResent() {
    attempts.add("a-1", "tx-1", AttemptState.FINALIZED, T0, 1, T0, LINE);
    attempts.add("a-2", "tx-2", AttemptState.CANCELLED, T0, 1, T0, LINE);
    clock.advance(Duration.ofMinutes(1));

    worker.scan();
    assertEquals(List.of(), commands.written);
  }
}
