package edu.northeastern.cs6510.boundaries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.Stack;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Private stores (quickstart V2, SC-002, US3-2): a service's account exists only on its own MySQL
 * instance, so every attempt to reach another service's store fails at authentication, and the
 * former cross-store views and procedure exist nowhere.
 */
@Tag("system")
class PrivateStoreAccessTest {
  private static final List<String> SERVICES = List.of("catalog", "checkout", "inventory", "analytics");
  /** MySQL ER_ACCESS_DENIED_ERROR and ER_DBACCESS_DENIED_ERROR. */
  private static final Set<Integer> ACCESS_DENIED = Set.of(1045, 1044);

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void everyAccountIsDeniedOnEveryOtherServicesInstance() {
    List<String> notDenied = new ArrayList<>();
    for (String account : SERVICES) {
      for (String instance : SERVICES) {
        if (account.equals(instance)) continue;
        String url = "jdbc:mysql://127.0.0.1:" + Stack.dbPort(instance) + "/?connectTimeout=3000";
        SQLException denied = assertThrows(SQLException.class,
            () -> DriverManager.getConnection(url, Stack.ownerUser(account), Stack.ownerPassword(account)).close(),
            account + " connected to " + instance + "-db");
        if (!ACCESS_DENIED.contains(denied.getErrorCode())) {
          notDenied.add(account + " -> " + instance + "-db failed with " + denied.getErrorCode() + " " + denied.getMessage());
        }
      }
    }
    assertEquals(List.of(), notDenied, "failures must be authentication denials, not other errors");
  }

  @Test
  void eachOwnerSeesOnlyItsOwnDatabase() throws SQLException {
    for (String service : SERVICES) {
      Set<String> databases = new TreeSet<>();
      try (Connection c = Stack.ownerConnection(service); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW DATABASES")) {
        while (rs.next()) databases.add(rs.getString(1));
      }
      assertEquals(new TreeSet<>(Set.of(service, "information_schema", "performance_schema")), databases, service + "-db");
    }
  }

  @Test
  void formerCrossStoreObjectsExistNowhere() throws SQLException {
    List<String> found = new ArrayList<>();
    for (String service : SERVICES) {
      try (Connection c = Stack.ownerConnection(service); Statement s = c.createStatement()) {
        try (ResultSet rs = s.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.VIEWS "
            + "WHERE TABLE_NAME IN ('active_items', 'low_stock', 'published_popularity', 'accepted_scan_outbox')")) {
          while (rs.next()) found.add("view " + rs.getString(1) + "." + rs.getString(2));
        }
        try (ResultSet rs = s.executeQuery("SELECT ROUTINE_SCHEMA, ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_NAME = 'fulfill_checkout'")) {
          while (rs.next()) found.add("procedure " + rs.getString(1) + "." + rs.getString(2));
        }
      }
    }
    assertTrue(found.isEmpty(), "cross-store objects still present: " + found);
  }
}
