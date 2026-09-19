package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.time.Instant;
import java.util.List;
public record LowStockResponse(int threshold, Instant generatedAt, List<LowStockAlertResponse> alerts) { }
