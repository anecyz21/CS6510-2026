package edu.northeastern.cs6510.selfcheckout.inventory;

import java.time.Instant;

public record LowStockAlert(String sku, String name, int currentStock, int threshold, Instant triggeredAt) { }
