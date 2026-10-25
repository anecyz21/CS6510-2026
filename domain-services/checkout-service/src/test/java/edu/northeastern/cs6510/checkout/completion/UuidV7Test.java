package edu.northeastern.cs6510.checkout.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {
  @Test
  void hasVersion7VariantAndEmbeddedTimestamp() {
    long millis = 1_761_350_400_123L;
    UUID id = UuidV7.at(millis);
    assertEquals(7, id.version());
    assertEquals(2, id.variant());                         // IETF variant, bits 10
    assertEquals(millis, id.getMostSignificantBits() >>> 16);
  }

  @Test
  void ordersByCreationMillisecond() {
    for (long t = 1_761_350_400_000L; t < 1_761_350_400_050L; t++) {
      String earlier = UuidV7.at(t).toString();
      String later = UuidV7.at(t + 1).toString();
      assertTrue(earlier.compareTo(later) < 0, earlier + " should sort before " + later);
    }
  }

  @Test
  void isRandomWithinOneMillisecond() {
    assertNotEquals(UuidV7.at(1L), UuidV7.at(1L));
    assertEquals(36, UuidV7.next().length());
  }
}
