package edu.northeastern.cs6510.checkout.outbox;

import edu.northeastern.cs6510.checkout.outbox.AcceptedScanEventMapper.OutboxRow;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes accepted-scan outbox rows to Kafka (research R4, contracts/accepted-scan-event.md).
 *
 * <ul>
 *   <li>One relay per Checkout database: a MySQL named lock held on a dedicated connection.</li>
 *   <li>Each pass selects only unpublished rows, so it always moves past published ones and picks
 *       up rows that committed late (no watermark, no fixed-oldest-batch starvation).</li>
 *   <li>A row is marked published only after the broker acknowledged it. A crash in between
 *       republishes it with the same eventId, which Analytics ignores as a duplicate.</li>
 *   <li>While Kafka is down, rows stay pending and scans keep committing (FR-025).</li>
 * </ul>
 */
@Component
public class OutboxRelay implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  static final String LOCK = "checkout.scan-relay";
  static final int BATCH = 500;
  private static final Duration BACKOFF_CAP = Duration.ofSeconds(5);
  private static final Duration LOCK_RETRY = Duration.ofSeconds(5);

  private final DataSource dataSource;
  private final JdbcTemplate db;
  private final KafkaTemplate<String, String> kafka;
  private final KafkaAdmin admin;
  private final AcceptedScanEventMapper mapper;
  private final Duration pollInterval;
  private final Duration retention;
  /** Fault injection (005 research R13): send every record twice. */
  private final boolean duplicateSends;
  private final AtomicLong pending = new AtomicLong();
  /** Test hook: runs after the broker acknowledged a batch and before it is marked. */
  volatile Runnable afterAcknowledged = () -> { };

  private volatile boolean running;
  private Thread worker;
  private Connection lockConnection;
  private boolean topicReady;
  private Instant lastPurge = Instant.EPOCH;
  private Instant lastGauge = Instant.EPOCH;

  @Autowired
  public OutboxRelay(DataSource dataSource, KafkaTemplate<String, String> acceptedScanTemplate, KafkaAdmin admin,
                     AcceptedScanEventMapper mapper, MeterRegistry meters,
                     @Value("${checkout.relay.poll-interval:200ms}") Duration pollInterval,
                     @Value("${checkout.relay.retention:24h}") Duration retention,
                     @Value("${messaging.faults.duplicate-sends:false}") boolean duplicateSends) {
    this.dataSource = dataSource;
    this.db = new JdbcTemplate(dataSource);
    this.kafka = acceptedScanTemplate;
    this.admin = admin;
    this.mapper = mapper;
    this.pollInterval = pollInterval;
    this.retention = retention;
    this.duplicateSends = duplicateSends;
    if (meters != null) Gauge.builder("checkout.outbox.pending", pending, AtomicLong::get).register(meters);
  }

  /** Unpublished rows, refreshed every few seconds by the relay. */
  public long pending() { return pending.get(); }

  @Override
  public void start() {
    running = true;
    worker = new Thread(this::loop, "outbox-relay");
    worker.setDaemon(true);
    worker.start();
  }

  @Override
  public void stop() {
    running = false;   // the loop finishes its current batch, then exits
    if (worker != null) {
      try {
        worker.join(15_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    releaseLock();
  }

  @Override
  public boolean isRunning() { return running; }

  private void loop() {
    Duration backoff = pollInterval;
    while (running) {
      try {
        if (!holdsLock()) {
          sleep(LOCK_RETRY);
          continue;
        }
        housekeeping();
        int published = publishOnce();
        backoff = pollInterval;
        if (published < BATCH) sleep(pollInterval);   // a full batch means more may be waiting
      } catch (RuntimeException e) {
        log.warn("outbox relay pass failed, retrying in {}: {}", backoff, e.toString());
        sleep(backoff);
        backoff = backoff.multipliedBy(2).compareTo(BACKOFF_CAP) > 0 ? BACKOFF_CAP : backoff.multipliedBy(2);
      }
    }
  }

  /**
   * One relay pass: send up to {@value #BATCH} unpublished rows in outbox order, wait for the
   * broker, then mark exactly the acknowledged rows. Returns the number of rows marked.
   */
  int publishOnce() {
    ensureTopic();
    List<OutboxRow> rows = db.query(
        "SELECT outbox_id, event_id, transaction_id, station_id, sku, accepted_at FROM accepted_scan_outbox "
            + "WHERE published_at IS NULL ORDER BY outbox_id LIMIT ?",
        (rs, n) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getTimestamp(6).toInstant()), BATCH);
    if (rows.isEmpty()) return 0;
    List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(rows.size());
    for (OutboxRow row : rows) {
      sends.add(kafka.send(mapper.record(row)));
      if (duplicateSends) kafka.send(mapper.record(row));
    }
    kafka.flush();
    List<Long> acknowledged = new ArrayList<>(rows.size());
    RuntimeException failure = null;
    for (int i = 0; i < rows.size(); i++) {
      try {
        sends.get(i).get(15, TimeUnit.SECONDS);
        acknowledged.add(rows.get(i).outboxId());
      } catch (Exception e) {
        if (failure == null) failure = new IllegalStateException("broker did not acknowledge outbox row " + rows.get(i).outboxId(), e);
      }
    }
    afterAcknowledged.run();
    if (!acknowledged.isEmpty()) {
      String ids = acknowledged.stream().map(String::valueOf).collect(Collectors.joining(","));
      db.update("UPDATE accepted_scan_outbox SET published_at = CURRENT_TIMESTAMP(6) WHERE outbox_id IN (" + ids + ")");
    }
    if (failure != null) throw failure;
    return acknowledged.size();
  }

  /** Creates the topic if a startup without Kafka could not; until then sends cannot succeed. */
  private void ensureTopic() {
    if (topicReady) return;
    if (!admin.initialize()) throw new IllegalStateException("kafka unavailable: topic not ensured");
    topicReady = true;
  }

  private void housekeeping() {
    Instant now = Instant.now();
    if (Duration.between(lastGauge, now).compareTo(Duration.ofSeconds(5)) >= 0) {
      Long count = db.queryForObject("SELECT COUNT(*) FROM accepted_scan_outbox WHERE published_at IS NULL", Long.class);
      pending.set(count == null ? 0 : count);
      lastGauge = now;
    }
    if (Duration.between(lastPurge, now).compareTo(Duration.ofSeconds(60)) >= 0) {
      Timestamp before = Timestamp.from(now.minus(retention));
      while (db.update("DELETE FROM accepted_scan_outbox WHERE published_at IS NOT NULL AND published_at < ? LIMIT 1000", before) == 1000) {
        // keep purging in small batches
      }
      lastPurge = now;
    }
  }

  /** Holds the named lock on a dedicated connection; re-acquires it if that connection was lost. */
  synchronized boolean holdsLock() {
    try {
      if (lockConnection != null && lockConnection.isValid(1)) return true;
      closeQuietly();
      Connection c = dataSource.getConnection();
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT GET_LOCK('" + LOCK + "', 0)")) {
        if (rs.next() && rs.getInt(1) == 1) {
          lockConnection = c;
          log.info("outbox relay holds {}", LOCK);
          return true;
        }
      }
      c.close();
      return false;
    } catch (SQLException e) {
      closeQuietly();
      return false;
    }
  }

  synchronized void releaseLock() {
    if (lockConnection == null) return;
    try (Statement s = lockConnection.createStatement()) {
      s.execute("DO RELEASE_LOCK('" + LOCK + "')");
    } catch (SQLException ignored) {
      // closing the connection releases it as well
    }
    closeQuietly();
  }

  private void closeQuietly() {
    if (lockConnection == null) return;
    try {
      lockConnection.close();
    } catch (SQLException ignored) {
      // already gone
    }
    lockConnection = null;
  }

  private void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
  }
}
