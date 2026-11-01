package edu.northeastern.cs6510.checkout.fault;

/**
 * Named points in the completion saga where tests inject failures (research R18, quickstart V7).
 * Production runs use a no-op; only the {@code fault-injection} profile can act on a checkpoint.
 */
public interface FaultInjector {
  enum FaultPoint { AFTER_FREEZE, AFTER_RESERVED_APPLIED, AFTER_COMMITTED_APPLIED }

  /** What an armed checkpoint does. */
  enum Action {
    /** Halt the JVM at once, as a crash would: no shutdown hooks, nothing more is written. */
    HALT
  }

  void checkpoint(FaultPoint point);

  /** Used everywhere except the fault-injection profile. */
  FaultInjector NONE = point -> { };
}
