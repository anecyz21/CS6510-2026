package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReceiptResponse(String transactionId, String stationId, int itemCount, BigDecimal totalAmount,
                              Instant startedAt, Instant completedAt, List<ReceiptLineResponse> lines) { }
