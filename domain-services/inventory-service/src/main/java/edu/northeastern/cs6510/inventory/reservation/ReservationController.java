package edu.northeastern.cs6510.inventory.reservation;

import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inventory internal API v1 reservation commands. Not routed by the gateway. */
@RestController
@RequestMapping("/internal/v1/reservations")
public class ReservationController {
  private final ReservationService reservations;

  public ReservationController(ReservationService reservations) { this.reservations = reservations; }

  /** 201 when this call created the reservation, 200 when it already existed (idempotent repeat). */
  @PutMapping("/{attemptId}")
  public ResponseEntity<ReservationV1> reserve(@PathVariable String attemptId, @RequestBody(required = false) ReserveRequestV1 request) {
    ReservationService.ReserveResult result = reservations.reserve(attemptId, request);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.reservation());
  }

  @PostMapping("/{attemptId}/commit")
  public ReservationV1 commit(@PathVariable String attemptId) { return reservations.commit(attemptId); }

  @GetMapping("/{attemptId}")
  public ReservationV1 get(@PathVariable String attemptId) { return reservations.get(attemptId); }
}
