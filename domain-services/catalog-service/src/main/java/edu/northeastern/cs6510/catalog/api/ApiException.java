package edu.northeastern.cs6510.catalog.api;

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

  public static ApiException invalidLimit(int limit) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit must be between 1 and 1000, was " + limit + ".");
  }

  public static ApiException skuNotFound(String sku) {
    return new ApiException(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND", "SKU " + sku + " is not in the catalog.");
  }
}
