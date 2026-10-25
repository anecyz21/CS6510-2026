package edu.northeastern.cs6510.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.northeastern.cs6510.contracts.catalog.v1.CatalogVersionV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductV1;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every canonical fixture must round-trip through its v1 record without losing a field, and the
 * records must tolerate unknown fields (research R16 compatibility rules).
 */
class FixtureCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  static Stream<Arguments> fixtures() {
    return Stream.of(
        Arguments.of("catalog-version-v1.json", CatalogVersionV1.class),
        Arguments.of("product-page-v1.json", ProductPageV1.class),
        Arguments.of("product-v1.json", ProductV1.class),
        Arguments.of("reserve-request-v1.json", ReserveRequestV1.class),
        Arguments.of("reservation-v1.json", ReservationV1.class),
        Arguments.of("accepted-scan-v1.json", AcceptedScanV1.class));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void roundTripsWithoutLosingFields(String fixture, Class<?> type) throws Exception {
    JsonNode expected = read(fixture);
    JsonNode actual = JSON.valueToTree(JSON.treeToValue(expected, type));
    assertSameContent(expected, actual, fixture);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void ignoresUnknownFields(String fixture, Class<?> type) throws Exception {
    ObjectNode extended = (ObjectNode) read(fixture);
    extended.put("futureField", 1);
    assertDoesNotThrow(() -> JSON.treeToValue(extended, type));
  }

  private static JsonNode read(String fixture) throws Exception {
    try (InputStream in = FixtureCompatibilityTest.class.getResourceAsStream("/fixtures/" + fixture)) {
      assertTrue(in != null, "missing fixture " + fixture);
      return JSON.readTree(in);
    }
  }

  /** Every field in the fixture is present with an equal value; timestamps compare as instants. */
  private static void assertSameContent(JsonNode expected, JsonNode actual, String path) {
    if (expected.isObject()) {
      assertTrue(actual.isObject(), path + " should be an object");
      for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        assertTrue(actual.has(field.getKey()), path + "." + field.getKey() + " was dropped");
        assertSameContent(field.getValue(), actual.get(field.getKey()), path + "." + field.getKey());
      }
    } else if (expected.isArray()) {
      assertEquals(expected.size(), actual.size(), path + " array size");
      for (int i = 0; i < expected.size(); i++) assertSameContent(expected.get(i), actual.get(i), path + "[" + i + "]");
    } else if (expected.isTextual() && instant(expected.asText()) != null) {
      assertEquals(instant(expected.asText()), instant(actual.asText()), path);
    } else if (expected.isNumber()) {
      assertTrue(actual.isNumber(), path + " should be a number");
      assertEquals(expected.decimalValue().stripTrailingZeros(), actual.decimalValue().stripTrailingZeros(), path);
    } else {
      assertEquals(expected, actual, path);
    }
  }

  private static Instant instant(String text) {
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException notATimestamp) {
      return null;
    }
  }
}
