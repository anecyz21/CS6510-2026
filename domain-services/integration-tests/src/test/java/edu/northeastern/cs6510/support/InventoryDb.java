package edu.northeastern.cs6510.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Read-only checks of Inventory's own database, through Inventory's owner credentials. */
public final class InventoryDb {
  private InventoryDb() { }

  /** The reservation state for an attempt, or null when Inventory never heard of it. */
  public static String reservationState(String attemptId) {
    return single("SELECT state FROM reservations WHERE attempt_id = ?", attemptId);
  }

  /**
   * Waits until Inventory has applied every committed sale to stock. Commits only queue their sales
   * (SaleApplier applies them every ~100 ms), so stock and SALE movements lag a commit briefly.
   */
  public static void awaitSalesApplied() {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (Integer.parseInt(single("SELECT COUNT(*) FROM sale_queue")) > 0) {
      if (System.nanoTime() > deadline) throw new IllegalStateException("committed sales still unapplied after 10 s");
      CheckoutDb.sleep(50);
    }
  }

  public static int saleMovements(String attemptId, String sku) {
    awaitSalesApplied();
    return Integer.parseInt(single(
        "SELECT COUNT(*) FROM inventory_movements WHERE movement_type = 'SALE' AND attempt_id = ? AND sku = ?", attemptId, sku));
  }

  private static String single(String sql, String... parameters) {
    try (Connection c = Stack.ownerConnection("inventory"); PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) s.setString(i + 1, parameters[i]);
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
