package edu.northeastern.cs6510.checkout.fault;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The injector of every normal and measured run: checkpoints do nothing. */
@Component
@Profile("!fault-injection")
public class NoopFaultInjector implements FaultInjector {
  @Override
  public void checkpoint(FaultPoint point) { }
}
