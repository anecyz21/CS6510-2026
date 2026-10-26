package edu.northeastern.cs6510.support;

import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Read-only checks of Checkout's own database, through Checkout's owner credentials. */
public final class CheckoutDb {
  private static final Set<String> TERMINAL = Set.of("FINALIZED", "REJECTED", "CANCELLED");

  private CheckoutDb() { }

  public record Attempt(String attemptId, String state) {
    public boolean terminal() { return TERMINAL.contains(state); }
  }

  public static List<Attempt> attempts(String transactionId) {
    List<Attempt> out = new ArrayList<>();
    query("SELECT attempt_id, state FROM completion_attempts WHERE transaction_id = ? ORDER BY created_at", transactionId,
        rs -> out.add(new Attempt(rs.getString(1), rs.getString(2))));
    return out;
  }

  public static String activeAttempt(String transactionId) {
    String[] out = {null};
    query("SELECT active_attempt_id FROM checkout_transactions WHERE transaction_id = ?", transactionId, rs -> out[0] = rs.getString(1));
    return out[0];
  }

  public static int receipts(String transactionId) {
    return count("SELECT COUNT(*) FROM receipts WHERE transaction_id = ?", transactionId);
  }

  public static int basketUnits(String transactionId) {
    return count("SELECT COALESCE(SUM(quantity), 0) FROM basket_lines WHERE transaction_id = ?", transactionId);
  }

  public static int acceptedScanEvents(String transactionId) {
    return count("SELECT COUNT(*) FROM accepted_scan_outbox WHERE transaction_id = ?", transactionId);
  }

  /** Waits until every attempt of the transaction is terminal and the basket is no longer frozen. */
  public static List<Attempt> awaitResolved(String transactionId, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      List<Attempt> attempts = attempts(transactionId);
      if (!attempts.isEmpty() && attempts.stream().allMatch(Attempt::terminal) && activeAttempt(transactionId) == null) return attempts;
      sleep(250);
    }
    fail("attempts of " + transactionId + " still unresolved after " + timeout + ": " + attempts(transactionId));
    return List.of();
  }

  /** Waits until the transaction's basket is frozen by an attempt. */
  public static String awaitActiveAttempt(String transactionId, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      String active = activeAttempt(transactionId);
      if (active != null) return active;
      sleep(20);
    }
    fail("no completion attempt froze " + transactionId + " within " + timeout);
    return null;
  }

  private interface RowHandler { void row(ResultSet rs) throws SQLException; }

  private static int count(String sql, String transactionId) {
    int[] out = {0};
    query(sql, transactionId, rs -> out[0] = rs.getInt(1));
    return out[0];
  }

  private static void query(String sql, String parameter, RowHandler handler) {
    try (Connection c = Stack.ownerConnection("checkout"); PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, parameter);
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) handler.row(rs);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
