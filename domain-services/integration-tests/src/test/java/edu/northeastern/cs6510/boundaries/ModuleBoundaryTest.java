package edu.northeastern.cs6510.boundaries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static module boundaries (quickstart V2–V3, constitution gate 2, research R15). Replaces the old
 * no-direct-call guard: services now must talk over versioned APIs and events, and must never reach
 * into another service's store.
 */
class ModuleBoundaryTest {
  private static final Path DS = Path.of("..").toAbsolutePath().normalize();
  private static final List<String> DOMAINS = List.of("catalog", "checkout", "inventory", "analytics");
  private static final Pattern SCHEMA_REFERENCE = Pattern.compile("\\b(catalog|checkout|inventory|analytics)\\.[a-z_]+\\b");
  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
  private static final Pattern SQL = Pattern.compile("\\b(SELECT|INSERT|UPDATE|DELETE|FROM|JOIN|CALL|INTO)\\b");
  private static final Pattern PROJECT_DEPENDENCY = Pattern.compile(
      "<dependency>\\s*<groupId>edu\\.northeastern\\.cs6510</groupId>\\s*<artifactId>([^<]+)</artifactId>");

  @Test
  void domainModulesDependOnNoOtherDomainModule() throws IOException {
    for (String domain : DOMAINS) {
      String pom = Files.readString(DS.resolve(domain + "-service/pom.xml"));
      Matcher m = PROJECT_DEPENDENCY.matcher(pom);
      while (m.find()) {
        assertEquals("service-contracts", m.group(1), domain + "-service must only share service-contracts, found " + m.group(1));
      }
    }
  }

  @Test
  void noServiceSqlNamesAnotherServicesSchema() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String domain : DOMAINS) {
      Path main = DS.resolve(domain + "-service/src/main");
      try (Stream<Path> files = Files.walk(main)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          String name = file.getFileName().toString();
          List<String> sqlTexts = new ArrayList<>();
          if (name.endsWith(".sql")) {
            sqlTexts.add(Files.readString(file));
          } else if (name.endsWith(".java")) {
            // Only string literals that look like SQL: package names and property keys such as
            // "${catalog.url}" are not database references.
            Matcher literal = STRING_LITERAL.matcher(Files.readString(file));
            while (literal.find()) if (SQL.matcher(literal.group()).find()) sqlTexts.add(literal.group());
          }
          for (String sql : sqlTexts) {
            Matcher ref = SCHEMA_REFERENCE.matcher(sql);
            while (ref.find()) {
              if (!ref.group(1).equals(domain)) violations.add(DS.relativize(file) + ": " + ref.group());
            }
          }
        }
      }
    }
    assertEquals(List.of(), violations, "SQL naming another service's schema");
  }

  @Test
  void serviceContractsHasNoPersistenceTypes() throws IOException {
    try (Stream<Path> files = Files.walk(DS.resolve("service-contracts/src/main"))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        for (String forbidden : List.of("import java.sql", "import javax.sql", "import org.springframework.jdbc",
            "org.springframework.stereotype.Repository")) {
          assertFalse(source.contains(forbidden), file.getFileName() + " must not use " + forbidden);
        }
      }
    }
    String pom = Files.readString(DS.resolve("service-contracts/pom.xml"));
    assertFalse(pom.contains("spring-boot-starter"), "service-contracts must not depend on Spring Boot starters");
  }

  @Test
  void gatewayHasNoPersistenceOrBrokerDependency() throws IOException {
    String pom = Files.readString(DS.resolve("api-gateway/pom.xml"));
    for (String artifact : List.of("jdbc", "flyway", "mysql", "kafka")) {
      assertFalse(pom.contains(artifact), "the gateway must not depend on " + artifact);
    }
  }

  @Test
  void gatewayContainsOnlyRoutingAndTranslationClasses() throws IOException {
    Set<String> allowed = Set.of("GatewayApplication.java", "UpstreamProxy.java", "CheckoutRouteHandler.java", "ReadModelRouteHandler.java");
    Set<String> unexpected = new TreeSet<>();
    try (Stream<Path> files = Files.walk(DS.resolve("api-gateway/src/main/java"))) {
      files.filter(p -> p.toString().endsWith(".java")).map(p -> p.getFileName().toString())
          .filter(n -> !allowed.contains(n) && !n.endsWith("Validation.java") && !n.endsWith("Config.java"))
          .forEach(unexpected::add);
    }
    assertTrue(unexpected.isEmpty(), "unexpected gateway classes (business logic belongs to the owning service): " + unexpected);
  }
}
