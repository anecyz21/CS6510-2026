package edu.northeastern.cs6510.checkout.fault;

import edu.northeastern.cs6510.checkout.inventory.InventoryUnavailableException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-only injector ({@code fault-injection} profile). Armed points live in memory, so a process
 * restarted after a halt does not halt again. A HALT point fires once; a LOSE point keeps
 * discarding the Inventory response until it is disarmed.
 */
@Component
@Profile("fault-injection")
public class HaltingFaultInjector implements FaultInjector {
  private static final Logger log = LoggerFactory.getLogger(HaltingFaultInjector.class);
  private final Map<FaultPoint, Action> armed = new ConcurrentHashMap<>();

  public void arm(FaultPoint point, Action action) { armed.put(point, action); }

  public void disarm(FaultPoint point) { armed.remove(point); }

  @Override
  public void checkpoint(FaultPoint point) {
    Action action = armed.get(point);
    if (action == null) return;
    if (action == Action.HALT && armed.remove(point, Action.HALT)) {
      log.warn("fault injection: halting the JVM at {}", point);
      Runtime.getRuntime().halt(137);
    }
    if (action == Action.LOSE) {
      throw new InventoryUnavailableException("fault injection: response lost at " + point);
    }
  }
}
