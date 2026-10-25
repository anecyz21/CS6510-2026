package edu.northeastern.cs6510.checkout.api;

import org.springframework.http.HttpStatus;

/** A failure with a documented status and error code. The message names identifiers only. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() { return status; }
  public String code() { return code; }

  public static ApiException transactionNotFound(String id) {
    return new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction " + id + " does not exist.");
  }

  public static ApiException transactionNotOpen(String id) {
    return new ApiException(HttpStatus.CONFLICT, "TRANSACTION_NOT_OPEN", "Transaction " + id + " is not open.");
  }

  public static ApiException transactionBusy(String id) {
    return new ApiException(HttpStatus.CONFLICT, "TRANSACTION_BUSY",
        "Transaction " + id + " is being completed; scan again after the completion returns.");
  }

  public static ApiException emptyBasket(String id) {
    return new ApiException(HttpStatus.CONFLICT, "EMPTY_BASKET", "Transaction " + id + " has no scanned units.");
  }

  public static ApiException insufficientStock(String id) {
    return new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
        "None of the remaining units in transaction " + id + " is available.");
  }

  public static ApiException skuNotFound(String sku) {
    return new ApiException(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND", "SKU " + sku + " is not in the catalog.");
  }

  public static ApiException serviceUnavailable(String what) {
    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
        "The " + what + " service is temporarily unavailable.");
  }

  public static ApiException missingStationId() {
    return new ApiException(HttpStatus.BAD_REQUEST, "MISSING_STATION_ID", "stationId is required.");
  }

  public static ApiException missingSku() {
    return new ApiException(HttpStatus.BAD_REQUEST, "MISSING_SKU", "sku is required.");
  }
}
