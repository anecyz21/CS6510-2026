package edu.northeastern.cs6510.inventory.api;

import edu.northeastern.cs6510.contracts.inventory.v1.InternalErrorV1;
import org.springframework.http.HttpStatus;

/** An internal-API failure whose body is an {@link InternalErrorV1}, e.g. a 409 carrying the reservation. */
public class InternalApiException extends RuntimeException {
  private final HttpStatus status;
  private final InternalErrorV1 body;

  public InternalApiException(HttpStatus status, InternalErrorV1 body) {
    super(body.message());
    this.status = status;
    this.body = body;
  }

  public HttpStatus status() { return status; }
  public InternalErrorV1 body() { return body; }
}
