package edu.northeastern.cs6510.checkout.completion;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7: 48-bit Unix milliseconds, version 7, variant 10, then random bits.
 * Ids created in later milliseconds sort after earlier ones, which keeps attempt and event keys
 * roughly time-ordered in their indexes.
 */
public final class UuidV7 {
  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() { }

  public static String next() { return at(System.currentTimeMillis()).toString(); }

  static UUID at(long epochMillis) {
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);
    long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;
    msb |= 0x7000L;                                            // version 7
    msb |= ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);    // 12 random bits (rand_a)
    long lsb = 0x8000_0000_0000_0000L;                          // variant 10
    lsb |= (random[2] & 0x3FL) << 56;
    for (int i = 3; i < 10; i++) lsb |= (random[i] & 0xFFL) << (8 * (9 - i));
    return new UUID(msb, lsb);
  }
}
