package edu.northeastern.cs6510.checkout.ops;

import edu.northeastern.cs6510.checkout.api.ApiException;
import edu.northeastern.cs6510.checkout.completion.AttemptLine;
import edu.northeastern.cs6510.checkout.completion.CompletionAttempt;
import edu.northeastern.cs6510.checkout.completion.CompletionAttemptRepository;
import edu.northeastern.cs6510.checkout.completion.CompletionAttemptRepository.OperatorFilter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout operations API v1 (contracts/checkout-operations-api.md): unresolved-attempt visibility
 * for operators (FR-018) and Checkout's half of the settled-state reconciliation. Read-only except
 * {@code retry}, which only makes an attempt due for recovery now. Not routed by the gateway.
 */
@RestController
@RequestMapping("/internal/v1/ops")
public class OperationsController {
  private static final int MAX_LIMIT = 1000;
  private final JdbcTemplate db;
  private final CompletionAttemptRepository attempts;

  public OperationsController(JdbcTemplate db, CompletionAttemptRepository attempts) {
    this.db = db;
    this.attempts = attempts;
  }

  @GetMapping("/completion-attempts")
  public Map<String, Object> list(@RequestParam(defaultValue = "unresolved") String state,
                                  @RequestParam(defaultValue = "100") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT + ".");
    }
    OperatorFilter filter = switch (state) {
      case "unresolved" -> OperatorFilter.UNRESOLVED;
      case "needsAttention" -> OperatorFilter.NEEDS_ATTENTION;
      case "all" -> OperatorFilter.ALL;
      default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATE", "state must be unresolved, needsAttention or all.");
    };
    List<Map<String, Object>> rows = attempts.list(filter, limit).stream().map(OperationsController::view).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generatedAt", Instant.now().toString());
    body.put("count", rows.size());
    body.put("attempts", rows);
    return body;
  }

  @GetMapping("/completion-attempts/{attemptId}")
  public Map<String, Object> get(@PathVariable String attemptId) {
    return attempts.find(attemptId).map(OperationsController::view)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Attempt " + attemptId + " does not exist."));
  }

  /** Makes a non-terminal attempt due for recovery now and clears its attention flag; never changes saga decisions. */
  @PostMapping("/completion-attempts/{attemptId}/retry")
  public ResponseEntity<Void> retry(@PathVariable String attemptId) {
    if (attempts.find(attemptId).isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Attempt " + attemptId + " does not exist.");
    }
    if (!attempts.requestRetry(attemptId, Instant.now())) {
      throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_TERMINAL", "Attempt " + attemptId + " is already resolved.");
    }
    return ResponseEntity.accepted().build();
  }

  @Transactional(readOnly = true)
  @GetMapping("/reconciliation-export")
  public Map<String, Object> reconciliationExport() {
    Integer unresolved = db.queryForObject(
        "SELECT COUNT(*) FROM completion_attempts WHERE state IN ('RESERVING', 'COMMITTING', 'CANCELLING')", Integer.class);
    Integer flags = db.queryForObject("SELECT COUNT(*) FROM checkout_transactions WHERE active_attempt_id IS NOT NULL", Integer.class);
    Map<String, Long> quantities = new LinkedHashMap<>();
    db.query("SELECT sku, SUM(quantity) FROM receipt_lines GROUP BY sku ORDER BY sku", rs -> { quantities.put(rs.getString(1), rs.getLong(2)); });
    List<String> finalized = db.queryForList("SELECT attempt_id FROM completion_attempts WHERE state = 'FINALIZED' ORDER BY attempt_id", String.class);
    Map<String, Object> export = new LinkedHashMap<>();
    export.put("generatedAt", Instant.now().toString());
    export.put("unresolvedAttempts", unresolved == null ? 0 : unresolved);
    export.put("activeAttemptFlags", flags == null ? 0 : flags);
    export.put("receiptQuantityBySku", quantities);
    export.put("finalizedAttemptIds", finalized);
    // Accepted-scan events (data-model R-6). outboxTotal counts published and pending rows; it is
    // exact only while the relay's 24 h purge of published rows has not run during the window.
    export.put("outboxPending", db.queryForObject("SELECT COUNT(*) FROM accepted_scan_outbox WHERE published_at IS NULL", Long.class));
    export.put("outboxTotal", db.queryForObject("SELECT COUNT(*) FROM accepted_scan_outbox", Long.class));
    return export;
  }

  private static Map<String, Object> view(CompletionAttempt a) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("attemptId", a.attemptId());
    v.put("transactionId", a.transactionId());
    v.put("state", a.state().name());
    v.put("createdAt", String.valueOf(a.createdAt()));
    v.put("updatedAt", String.valueOf(a.updatedAt()));
    v.put("commandSends", a.commandSends());
    v.put("nextRetryAt", String.valueOf(a.nextRetryAt()));
    v.put("needsAttention", a.needsAttention());
    v.put("lastError", a.lastError());
    v.put("lines", a.lines().stream().map(OperationsController::line).toList());
    return v;
  }

  private static Map<String, Object> line(AttemptLine l) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("sku", l.sku());
    v.put("requestedQuantity", l.requestedQuantity());
    v.put("reservedQuantity", l.reservedQuantity());
    return v;
  }
}
