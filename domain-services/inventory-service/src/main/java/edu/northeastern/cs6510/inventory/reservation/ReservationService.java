package edu.northeastern.cs6510.inventory.reservation;

import edu.northeastern.cs6510.contracts.inventory.v1.InternalErrorV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationStateV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import edu.northeastern.cs6510.inventory.api.ApiException;
import edu.northeastern.cs6510.inventory.api.InternalApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reservation commands keyed by Checkout's attempt id (contracts/inventory-internal-api.md).
 * Each command is one Inventory local transaction and may be repeated any number of times: a
 * repeat returns the stored outcome and never applies a second stock effect. Reservations never
 * expire on their own.
 */
@Service
public class ReservationService {
  static final int MAX_DISTINCT_SKUS = 1000;
  private static final int MAX_LOCK_ATTEMPTS = 3;

  private final ReservationRepository repository;
  private final TransactionTemplate transactions;
  private final MeterRegistry meters;
  private final Clock clock;

  @Autowired
  public ReservationService(ReservationRepository repository, PlatformTransactionManager transactionManager, MeterRegistry meters) {
    this(repository, transactionManager, meters, Clock.systemUTC());
  }

  ReservationService(ReservationRepository repository, PlatformTransactionManager transactionManager, MeterRegistry meters, Clock clock) {
    this.repository = repository;
    this.transactions = new TransactionTemplate(transactionManager);
    this.meters = meters;
    this.clock = clock;
  }

  /** Outcome of a reserve command: the stored resource and whether this call created it. */
  public record ReserveResult(ReservationV1 reservation, boolean created) { }

  public ReserveResult reserve(String attemptId, ReserveRequestV1 request) {
    // INSERT IGNORE would silently truncate an oversized key, so reject it up front.
    if (attemptId == null || attemptId.isBlank() || attemptId.length() > 64) throw invalid();
    NavigableMap<String, Integer> requested = validate(request);
    return withLockRetries("reserve", () -> transactions.execute(status -> reserveOnce(attemptId, request.transactionId(), requested)));
  }

  /**
   * Creates the reservation row, then reserves line by line in descending SKU order, each with one
   * conditional update that locks only its row. Nothing is read with FOR UPDATE, and after the last
   * position update only the batched line insert and the commit remain, so hot rows are held briefly.
   */
  private ReserveResult reserveOnce(String attemptId, String transactionId, NavigableMap<String, Integer> requested) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);   // as stored in TIMESTAMP(6)
    if (!repository.insertReservation(attemptId, transactionId, ReservationStateV1.RESERVED, now)) {
      // Existing id: return the stored outcome unchanged, whatever its state (including a tombstone).
      repository.lockReservation(attemptId);
      ReservationV1 existing = repository.find(attemptId).orElseThrow();
      if (!existing.lines().isEmpty() && !sameLines(existing.lines(), requested)) {
        throw conflict("RESERVATION_MISMATCH", "Reservation " + attemptId + " already exists with different lines.", existing);
      }
      return new ReserveResult(existing, false);
    }
    Map<String, Integer> granted = new HashMap<>();
    int total = 0;
    for (Map.Entry<String, Integer> line : requested.descendingMap().entrySet()) {
      int grant = repository.reserveUpTo(line.getKey(), line.getValue());
      granted.put(line.getKey(), grant);
      total += grant;
    }
    List<ReservationLineV1> lines = requested.entrySet().stream()   // ascending, as find() returns them
        .map(line -> new ReservationLineV1(line.getKey(), line.getValue(), granted.get(line.getKey()))).toList();
    repository.insertLines(attemptId, lines);
    if (total == 0) {
      repository.updateState(attemptId, ReservationStateV1.RELEASED, now);
      return new ReserveResult(new ReservationV1(attemptId, transactionId, ReservationStateV1.RELEASED, lines, now, now), true);
    }
    return new ReserveResult(new ReservationV1(attemptId, transactionId, ReservationStateV1.RESERVED, lines, now, null), true);
  }

  public ReservationV1 commit(String attemptId) {
    return withLockRetries("commit", () -> transactions.execute(status -> commitOnce(attemptId)));
  }

  /**
   * Marks the reservation COMMITTED and queues its held lines for {@link SaleApplier}, which moves
   * them from reserved to physical stock shortly afterwards. No position row is locked here, so a
   * completion locks hot rows once (in reserve), not twice. The sale is final from this commit: the
   * quantity can no longer be released, and it stays out of availability until it is applied.
   */
  private ReservationV1 commitOnce(String attemptId) {
    ReservationStateV1 state = repository.lockReservation(attemptId).orElseThrow(() -> notFound(attemptId));
    ReservationV1 reservation = repository.find(attemptId).orElseThrow();
    switch (state) {
      case COMMITTED:
        return reservation;   // idempotent repeat: no second stock effect
      case RELEASED:
      case CANCELLED:
        throw conflict("RESERVATION_NOT_COMMITTABLE", "Reservation " + attemptId + " is " + state + " and cannot be committed.", reservation);
      case RESERVED:
      default:
        break;
    }
    List<ReservationLineV1> held = reservation.lines().stream().filter(l -> l.reservedQuantity() > 0).toList();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    repository.enqueueSales(attemptId, held, now);
    repository.updateState(attemptId, ReservationStateV1.COMMITTED, now);
    return new ReservationV1(reservation.attemptId(), reservation.transactionId(), ReservationStateV1.COMMITTED,
        reservation.lines(), reservation.createdAt(), now);
  }

  /**
   * Releases an uncommitted reservation. An unknown id gets a CANCELLED tombstone, so a reserve
   * that arrives later for the same id reserves nothing. Locks like commit (reservation row first,
   * then each position by a single-row update), so commit and cancel of one id are mutually exclusive.
   */
  public ReservationV1 cancel(String attemptId) {
    return withLockRetries("cancel", () -> transactions.execute(status -> cancelOnce(attemptId)));
  }

  private ReservationV1 cancelOnce(String attemptId) {
    Instant now = clock.instant();
    // The tombstone does not know its transaction: cancel carries no body.
    if (repository.insertReservation(attemptId, "", ReservationStateV1.CANCELLED, now)) {
      repository.updateState(attemptId, ReservationStateV1.CANCELLED, now);
      return repository.find(attemptId).orElseThrow();
    }
    ReservationStateV1 state = repository.lockReservation(attemptId).orElseThrow();
    ReservationV1 reservation = repository.find(attemptId).orElseThrow();
    switch (state) {
      case RELEASED:
      case CANCELLED:
        return reservation;   // idempotent repeat
      case COMMITTED:
        throw conflict("RESERVATION_COMMITTED", "Reservation " + attemptId + " is committed; finalize it instead of compensating.", reservation);
      case RESERVED:
      default:
        break;
    }
    List<ReservationLineV1> held = reservation.lines().stream().filter(l -> l.reservedQuantity() > 0)
        .sorted(Comparator.comparing(ReservationLineV1::sku).reversed()).toList();   // the repository's lock order
    for (ReservationLineV1 line : held) {
      if (!repository.releaseLine(line.sku(), line.reservedQuantity())) {
        throw new IllegalStateException("reserved stock for " + line.sku() + " is lower than reservation " + attemptId);
      }
    }
    repository.updateState(attemptId, ReservationStateV1.RELEASED, now);
    return repository.find(attemptId).orElseThrow();
  }

  public ReservationV1 get(String attemptId) {
    return repository.find(attemptId).orElseThrow(() -> notFound(attemptId));
  }

  /** 1–1,000 distinct SKUs, each with quantity >= 1, returned in ascending SKU order. */
  private static NavigableMap<String, Integer> validate(ReserveRequestV1 request) {
    if (request == null || request.transactionId() == null || request.transactionId().isBlank()
        || request.lines() == null || request.lines().isEmpty() || request.lines().size() > MAX_DISTINCT_SKUS) {
      throw invalid();
    }
    NavigableMap<String, Integer> lines = new TreeMap<>();
    Set<String> seen = new HashSet<>();
    for (ReserveLineV1 line : request.lines()) {
      if (line == null || line.sku() == null || line.sku().isBlank() || line.quantity() < 1 || !seen.add(line.sku())) throw invalid();
      lines.put(line.sku(), line.quantity());
    }
    return lines;
  }

  private static boolean sameLines(List<ReservationLineV1> stored, Map<String, Integer> requested) {
    if (stored.size() != requested.size()) return false;
    return stored.stream().allMatch(l -> Integer.valueOf(l.requestedQuantity()).equals(requested.get(l.sku())));
  }

  /** Lock-wait and deadlock victims are retried; the command is safe to repeat because it is keyed by attempt id. */
  private <T> T withLockRetries(String operation, Supplier<T> command) {
    for (int attempt = 1; ; attempt++) {
      try {
        return command.get();
      } catch (PessimisticLockingFailureException victim) {
        if (attempt == MAX_LOCK_ATTEMPTS) {
          throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Inventory is busy; retry the same command.");
        }
        meters.counter("inventory.db.retries", "operation", operation).increment();
      }
    }
  }

  private static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST",
        "A reservation needs a transactionId and 1 to " + MAX_DISTINCT_SKUS + " distinct SKUs, each with quantity >= 1.");
  }

  private static InternalApiException notFound(String attemptId) {
    return new InternalApiException(HttpStatus.NOT_FOUND,
        new InternalErrorV1("RESERVATION_NOT_FOUND", "Reservation " + attemptId + " does not exist.", null));
  }

  private static InternalApiException conflict(String code, String message, ReservationV1 reservation) {
    return new InternalApiException(HttpStatus.CONFLICT, new InternalErrorV1(code, message, reservation));
  }
}
