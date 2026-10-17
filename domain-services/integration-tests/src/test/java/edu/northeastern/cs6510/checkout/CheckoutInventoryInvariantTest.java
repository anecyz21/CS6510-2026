package edu.northeastern.cs6510.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs after a concurrent checkout workload against the provisioned central database. */
@EnabledIfEnvironmentVariable(named = "RUN_CHECKOUT_DATABASE_TESTS", matches = "true")
class CheckoutInventoryInvariantTest {
  @Test void stockAndCompletedReceiptsReconcilePerSku() throws Exception {
    try (Connection connection = connection(); var statement = connection.createStatement()) {
      ResultSet bounds = statement.executeQuery("""
          SELECT COUNT(*) FROM inventory.inventory_positions
          WHERE reserved_stock > available_stock OR available_stock < 0 OR available_stock > initial_stock
          """);
      bounds.next();
      assertEquals(0, bounds.getInt(1), "stock bounds must hold for every SKU");

      ResultSet reconciliation = statement.executeQuery("""
          SELECT COUNT(*) FROM inventory.inventory_positions p
          LEFT JOIN (
            SELECT sku, SUM(quantity) completed_quantity
            FROM checkout.receipt_lines GROUP BY sku
          ) r ON r.sku = p.sku
          WHERE p.initial_stock - p.available_stock <> COALESCE(r.completed_quantity, 0)
          """);
      reconciliation.next();
      assertEquals(0, reconciliation.getInt(1), "only completed receipt lines may reduce stock");
    }
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(required("CHECKOUT_TEST_DB_URL"), required("CHECKOUT_TEST_DB_USERNAME"), required("CHECKOUT_TEST_DB_PASSWORD"));
  }

  private String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set when database integration tests are enabled");
    return value;
  }
}
