package edu.northeastern.cs6510.analytics.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps every failure to an {@link ApiError} body; stack traces never reach clients. */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
  }

  /** This service's own database is unreachable, so no business effect was confirmed (amendment A6). */
  @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class})
  ResponseEntity<ApiError> ownStoreUnavailable(Exception e) {
    log.warn("database unavailable: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiError("SERVICE_UNAVAILABLE", "The service's data store is temporarily unavailable."));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
  ResponseEntity<ApiError> unreadable(Exception e) {
    return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "The request could not be read."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> other(Exception e) {
    if (e instanceof ErrorResponse framework) {
      HttpStatusCode status = framework.getStatusCode();
      HttpStatus known = HttpStatus.resolve(status.value());
      return ResponseEntity.status(status)
          .body(new ApiError(known == null ? "HTTP_" + status.value() : known.name(), "The request was not accepted."));
    }
    log.error("unexpected error", e);
    return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "Unexpected error."));
  }
}
