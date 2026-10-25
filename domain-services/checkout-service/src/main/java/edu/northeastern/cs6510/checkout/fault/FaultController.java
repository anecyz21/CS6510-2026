package edu.northeastern.cs6510.checkout.fault;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Arms and disarms saga fault points. Exists only under the {@code fault-injection} profile; never routed by the gateway. */
@RestController
@Profile("fault-injection")
public class FaultController {
  private final HaltingFaultInjector faults;

  public FaultController(HaltingFaultInjector faults) { this.faults = faults; }

  @PostMapping("/internal/v1/ops/faults/{point}")
  public ResponseEntity<Void> arm(@PathVariable FaultInjector.FaultPoint point,
                                  @RequestParam(defaultValue = "HALT") FaultInjector.Action action) {
    faults.arm(point, action);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/internal/v1/ops/faults/{point}")
  public ResponseEntity<Void> disarm(@PathVariable FaultInjector.FaultPoint point) {
    faults.disarm(point);
    return ResponseEntity.noContent().build();
  }
}
