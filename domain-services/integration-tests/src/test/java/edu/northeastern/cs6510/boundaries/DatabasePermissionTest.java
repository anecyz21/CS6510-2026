package edu.northeastern.cs6510.boundaries;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class DatabasePermissionTest {
  @Test void bootstrapCreatesSeparateServiceAccounts() throws Exception {
    String sql = Files.readString(Path.of("../deployment/mysql/init/001-service-boundaries.sql"));
    assertTrue(sql.contains("catalog_service") && sql.contains("checkout_service"));
    assertTrue(sql.contains("inventory_service") && sql.contains("analytics_service"));
    String grants = Files.readString(Path.of("../deployment/scripts/grant-cross-domain-interfaces.sh"));
    assertTrue(grants.contains("GRANT SELECT ON catalog.active_items"));
    assertTrue(grants.contains("GRANT EXECUTE ON PROCEDURE inventory.fulfill_checkout"));
    assertTrue(grants.contains("GRANT SELECT ON checkout.accepted_scan_outbox"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "RUN_DATABASE_BOUNDARY_TESTS", matches = "true")
  void checkoutCanReadOnlyItsPublishedInterfaces() throws Exception {
    try (Connection connection = connect("checkout_service", "checkout_service")) {
      connection.createStatement().executeQuery("SELECT * FROM catalog.active_items LIMIT 1");
      assertDenied(connection, "UPDATE catalog.catalog_items SET name = name WHERE 1 = 0");
      assertDenied(connection, "UPDATE inventory.inventory_positions SET available_stock = available_stock WHERE 1 = 0");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "RUN_DATABASE_BOUNDARY_TESTS", matches = "true")
  void analyticsCannotModifyCheckoutOutbox() throws Exception {
    try (Connection connection = connect("analytics_service", "analytics_service")) {
      connection.createStatement().executeQuery("SELECT * FROM checkout.accepted_scan_outbox LIMIT 1");
      assertDenied(connection, "UPDATE checkout.accepted_scan_events SET sku = sku WHERE 1 = 0");
    }
  }

  private Connection connect(String username, String password) throws SQLException {
    String url = System.getenv().getOrDefault("BOUNDARY_DB_URL", "jdbc:mysql://localhost:3306");
    return DriverManager.getConnection(url, username, password);
  }

  private void assertDenied(Connection connection, String statement) {
    assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(statement));
  }
}
