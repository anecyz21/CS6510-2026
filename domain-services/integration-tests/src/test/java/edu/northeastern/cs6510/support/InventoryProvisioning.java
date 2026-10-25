package edu.northeastern.cs6510.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Controlled stock changes for system tests, through the inventory owner's own credentials (the
 * same statements as {@code deployment/provisioning/restock-sku.sql}). Both initial and physical
 * stock move by the same delta and a PROVISION movement is written, so reconciliation still holds.
 */
public final class InventoryProvisioning {
  private InventoryProvisioning() { }

  /** Sets the SKU's physical stock to {@code quantity}. Requires no stock to be reserved above it. */
  public static void setStock(String sku, int quantity) {
    Position p = position(sku);
    adjust(sku, quantity - p.physical());
  }

  /** Adds {@code quantity} units to the SKU's physical stock. */
  public static void addStock(String sku, int quantity) { adjust(sku, quantity); }

  public static Position position(String sku) {
    try (Connection c = Stack.ownerConnection("inventory");
         PreparedStatement s = c.prepareStatement("SELECT initial_stock, physical_stock, reserved_stock FROM inventory_positions WHERE sku = ?")) {
      s.setString(1, sku);
      try (ResultSet rs = s.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("no inventory position for " + sku);
        return new Position(rs.getInt(1), rs.getInt(2), rs.getInt(3));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void adjust(String sku, int delta) {
    if (delta == 0) return;
    try (Connection c = Stack.ownerConnection("inventory")) {
      c.setAutoCommit(false);
      try (PreparedStatement update = c.prepareStatement(
               "UPDATE inventory_positions SET initial_stock = initial_stock + ?, physical_stock = physical_stock + ? WHERE sku = ?");
           PreparedStatement movement = c.prepareStatement(
               "INSERT INTO inventory_movements (sku, attempt_id, movement_type, quantity_delta, physical_after, occurred_at) "
                   + "SELECT sku, NULL, 'PROVISION', ?, physical_stock, CURRENT_TIMESTAMP(6) FROM inventory_positions WHERE sku = ?")) {
        update.setInt(1, delta);
        update.setInt(2, delta);
        update.setString(3, sku);
        if (update.executeUpdate() != 1) throw new IllegalArgumentException("no inventory position for " + sku);
        movement.setInt(1, delta);
        movement.setString(2, sku);
        movement.executeUpdate();
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Position(int initial, int physical, int reserved) { }
}
