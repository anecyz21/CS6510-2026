package edu.northeastern.cs6510.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Map;

/**
 * The canonical v1 fixtures, shared with provider and consumer tests through this module's
 * test-jar (research R16). A provider must still produce every fixture field; a consumer must
 * still read a fixture that has gained fields it does not know.
 */
public final class Fixtures {
  public static final ObjectMapper JSON = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Fixtures() { }

  /** Parsed fixture from {@code /fixtures/<name>}. */
  public static JsonNode read(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) throw new IllegalArgumentException("missing fixture " + name);
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The fixture's JSON with an extra field a future compatible version might add. */
  public static String withUnknownField(String name) {
    ObjectNode node = (ObjectNode) read(name);
    node.put("futureField", "added-in-a-later-v1");
    return node.toString();
  }

  /** Every field of {@code fixture} (recursively; arrays by their first element) is present in {@code actual}. */
  public static void assertCovers(JsonNode fixture, JsonNode actual, String path) {
    if (fixture.isObject()) {
      assertTrue(actual != null && actual.isObject(), path + " should be an object but was " + actual);
      for (Iterator<Map.Entry<String, JsonNode>> it = fixture.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        assertTrue(actual.has(field.getKey()), path + "." + field.getKey() + " is missing from the provider's response");
        assertCovers(field.getValue(), actual.get(field.getKey()), path + "." + field.getKey());
      }
    } else if (fixture.isArray() && fixture.size() > 0) {
      assertTrue(actual != null && actual.isArray() && actual.size() > 0, path + " should be a non-empty array");
      assertCovers(fixture.get(0), actual.get(0), path + "[0]");
    }
  }
}
