package edu.northeastern.cs6510.checkout;

import edu.northeastern.cs6510.checkout.api.ApiException;
import org.springframework.stereotype.Service;

/** Transaction lifecycle. Scanning lives in {@code scan.ScanService}; completion arrives with the saga (US1). */
@Service
public class CheckoutService {
  private final CheckoutRepository repository;

  public CheckoutService(CheckoutRepository repository) { this.repository = repository; }

  public CheckoutRepository.Transaction start(String stationId) {
    if (stationId == null || stationId.isBlank()) throw ApiException.missingStationId();
    return repository.start(stationId);
  }

  public CheckoutRepository.Transaction get(String transactionId) {
    return repository.transaction(transactionId).orElseThrow(() -> ApiException.transactionNotFound(transactionId));
  }

  public CheckoutRepository.Totals basketTotals(String transactionId) { return repository.basketTotals(transactionId); }

  /**
   * The database-procedure completion was removed with the cross-store objects. Until the
   * completion saga replaces it (task T059), completion is truthfully unavailable rather than
   * reporting a purchase it cannot confirm.
   */
  public void complete(String transactionId) {
    get(transactionId);
    throw ApiException.serviceUnavailable("completion");
  }
}
