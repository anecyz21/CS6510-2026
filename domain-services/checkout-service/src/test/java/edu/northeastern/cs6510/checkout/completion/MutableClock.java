package edu.northeastern.cs6510.checkout.completion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand. */
public final class MutableClock extends Clock {
  private Instant now;

  public MutableClock(Instant now) { this.now = now; }

  public void advance(Duration d) { now = now.plus(d); }

  @Override public Instant instant() { return now; }
  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { return this; }
}
