package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(String transactionId, String stationId, String status, int itemCount,
                                  BigDecimal runningTotal, Instant startedAt) { }
