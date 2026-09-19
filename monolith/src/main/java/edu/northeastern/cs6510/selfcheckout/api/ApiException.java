package edu.northeastern.cs6510.selfcheckout.api;

import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    private final String errorCode;
    private final HttpStatus status;

    public ApiException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException missingStationId() {
        return new ApiException("MISSING_STATION_ID", HttpStatus.BAD_REQUEST, "stationId is required.");
    }

    public static ApiException invalidLimit() {
        return new ApiException("INVALID_LIMIT", HttpStatus.BAD_REQUEST, "limit must be at least 1.");
    }

    public static ApiException transactionNotFound(String transactionId) {
        return new ApiException("TRANSACTION_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Transaction " + transactionId + " was not found.");
    }

    public static ApiException skuNotFound(String sku) {
        return new ApiException("SKU_NOT_FOUND", HttpStatus.NOT_FOUND, "SKU " + sku + " was not found.");
    }

    public static ApiException transactionNotOpen(String transactionId) {
        return new ApiException("TRANSACTION_NOT_OPEN", HttpStatus.CONFLICT,
                "Transaction " + transactionId + " is not open.");
    }

    public static ApiException emptyBasket(String transactionId) {
        return new ApiException("EMPTY_BASKET", HttpStatus.CONFLICT,
                "Transaction " + transactionId + " has no scanned items.");
    }

    public static ApiException insufficientStock(String transactionId) {
        return new ApiException("INSUFFICIENT_STOCK", HttpStatus.CONFLICT,
                "Transaction " + transactionId + " cannot be fulfilled from available stock.");
    }
}
