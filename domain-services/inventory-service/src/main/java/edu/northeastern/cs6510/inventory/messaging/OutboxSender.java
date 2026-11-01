package edu.northeastern.cs6510.inventory.messaging;

import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@code reply_outbox} rows to {@code inventory.replies.v1} (005 research R5).
 *
 * <ul>
 *   <li><b>After commit</b>: a transaction that wrote a row registers its attempt id; once it commits,
 *       the sender thread publishes that attempt's unsent rows at once, so a reply waits for no poll.</li>
 *   <li><b>Per-attempt order</b> (FR-010): the thread always sends every unsent row of an attempt, oldest
 *       first, never a newer row ahead of an older one. One thread does all sending, sweeps included,
 *       so the two paths never interleave.</li>
 *   <li><b>Sweep</b>: every second, rows still unsent two seconds after they were written are sent,
 *       which covers a crash or broker outage between commit and send. Receivers ignore repeats.</li>
 *   <li>A row is marked {@code published_at} only after the broker acknowledged it.</li>
 * </ul>
 */
@Component
public class OutboxSender implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);
  static final String TABLE = "reply_outbox";
  static final String TOPIC = InventoryReplyV1.TOPIC;
  static final String PRODUCER = "inventory";
  static final String CAUSATION = "causation_id";
  static final int BATCH = 500;
  private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);
  private static final Duration SWEEP_AGE = Duration.ofSeconds(2);
  private static final Duration DRAIN_LIMIT = Duration.ofSeconds(10);

  private final JdbcTemplate db;
  private final KafkaTemplate<String, String> kafka;
  private final Duration retention;
  private final boolean duplicateSends;
  private final boolean holdSends;
  private final LinkedBlockingQueue<String> committed = new LinkedBlockingQueue<>();
  private final AtomicLong pending = new AtomicLong();

  private volatile boolean running;
  private Thread worker;
  private Instant lastPurge = Instant.EPOCH;

  public OutboxSender(JdbcTemplate db, KafkaTemplate<String, String> replyTemplate, MeterRegistry meters,
                      @Value("${messaging.outbox.retention:24h}") Duration retention,
                      @Value("${messaging.faults.duplicate-sends:false}") boolean duplicateSends,
                      @Value("${messaging.faults.hold-sends:false}") boolean holdSends) {
    this.db = db;
    this.kafka = replyTemplate;
    this.retention = retention;
    this.duplicateSends = duplicateSends;
    this.holdSends = holdSends;
    if (meters != null) Gauge.builder("inventory.reply_outbox.pending", pending, AtomicLong::get).register(meters);
  }

  /**
   * Called inside the transaction that wrote a row for {@code attemptId}: the attempt is handed to
   * the sender thread when that transaction commits, and dropped if it rolls back.
   */
  public void afterCommit(String attemptId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      committed.offer(attemptId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() { committed.offer(attemptId); }
    });
  }

  /** Unsent rows, refreshed by every sweep. */
  public long pending() { return pending.get(); }

  @Override
  public void start() {
    running = true;
    worker = new Thread(this::loop, "reply-outbox-sender");
    worker.setDaemon(true);
    worker.start();
  }

  /** Stops after the listeners and the web server (lower phase), draining what they committed. */
  @Override
  public void stop() {
    running = false;
    if (worker != null) {
      try {
        worker.join(DRAIN_LIMIT.toMillis() + 2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() { return running; }

  @Override
  public int getPhase() { return Integer.MAX_VALUE - 2048; }

  private void loop() {
    Instant nextSweep = Instant.now();
    Instant drainUntil = null;
    while (true) {
      if (!running) {
        if (drainUntil == null) drainUntil = Instant.now().plus(DRAIN_LIMIT);
        if (committed.isEmpty() || Instant.now().isAfter(drainUntil)) return;
      }
      try {
        Set<String> attempts = new LinkedHashSet<>();
        String first = committed.poll(Math.max(1, Duration.between(Instant.now(), nextSweep).toMillis()), TimeUnit.MILLISECONDS);
        if (first != null) {
          attempts.add(first);
          String next;
          while (attempts.size() < BATCH && (next = committed.poll()) != null) attempts.add(next);
          sendForAttempts(attempts);
        }
        if (!Instant.now().isBefore(nextSweep)) {
          if (running) sweep();
          nextSweep = Instant.now().plus(SWEEP_INTERVAL);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // Nothing is lost: the rows stay unsent and the next sweep picks them up.
        log.warn("{} send failed, the sweep will retry: {}", TABLE, e.toString());
        nextSweep = Instant.now().plus(SWEEP_INTERVAL);
      }
    }
  }

  private void sendForAttempts(Set<String> attempts) {
    if (holdSends) return;
    String marks = attempts.stream().map(a -> "?").collect(Collectors.joining(","));
    send(db.query(select("attempt_id IN (" + marks + ") AND published_at IS NULL"), this::row, attempts.toArray()));
  }

  void sweep() {
    Long count = db.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE published_at IS NULL", Long.class);
    pending.set(count == null ? 0 : count);
    if (!holdSends && pending.get() > 0) {
      Timestamp olderThan = Timestamp.from(Instant.now().minus(SWEEP_AGE));
      send(db.query(select("published_at IS NULL AND created_at < ?"), this::row, olderThan));
    }
    if (Duration.between(lastPurge, Instant.now()).toMinutes() >= 10) {
      db.update("DELETE FROM " + TABLE + " WHERE published_at IS NOT NULL AND published_at < ? LIMIT 10000",
          Timestamp.from(Instant.now().minus(retention)));
      lastPurge = Instant.now();
    }
  }

  private static String select(String where) {
    return "SELECT outbox_id, message_id, " + CAUSATION + ", attempt_id, message_type, payload, created_at FROM " + TABLE
        + " WHERE " + where + " ORDER BY outbox_id LIMIT " + BATCH;
  }

  private record Row(long outboxId, String messageId, String causationId, String attemptId, String type, String payload, Instant createdAt) { }

  private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
        rs.getTimestamp(7).toInstant());
  }

  /** Sends the rows in order, waits for the broker, then marks exactly the acknowledged ones. */
  private void send(List<Row> rows) {
    if (rows.isEmpty()) return;
    List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(rows.size());
    for (Row row : rows) {
      sends.add(kafka.send(record(row)));
      if (duplicateSends) kafka.send(record(row));
    }
    kafka.flush();
    List<Long> acknowledged = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      try {
        sends.get(i).get(10, TimeUnit.SECONDS);
        acknowledged.add(rows.get(i).outboxId());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception notAcknowledged) {
        // Later rows of the same attempt may have been acknowledged; they are still marked, and
        // the receiver's state checks make the resend of this one harmless.
        log.debug("{} row {} not acknowledged: {}", TABLE, rows.get(i).outboxId(), notAcknowledged.toString());
      }
    }
    if (acknowledged.isEmpty()) return;
    String marks = acknowledged.stream().map(id -> "?").collect(Collectors.joining(","));
    List<Object> args = new ArrayList<>(acknowledged.size() + 1);
    args.add(Timestamp.from(Instant.now()));
    args.addAll(acknowledged);
    db.update("UPDATE " + TABLE + " SET published_at = ? WHERE outbox_id IN (" + marks + ")", args.toArray());
  }

  private ProducerRecord<String, String> record(Row row) {
    ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, row.attemptId(), row.payload());
    new Envelope(row.messageId(), row.type(), 1, PRODUCER, row.createdAt(), row.attemptId(), row.causationId())
        .toHeaders().forEach((name, value) -> record.headers().add(name, value));
    return record;
  }
}
