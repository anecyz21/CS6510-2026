package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.time.Instant;
public record LowStockAlertResponse(String sku, String name, int currentStock, int threshold, Instant triggeredAt) { }
