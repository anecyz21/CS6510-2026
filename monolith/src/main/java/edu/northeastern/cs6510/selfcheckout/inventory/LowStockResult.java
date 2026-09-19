package edu.northeastern.cs6510.selfcheckout.inventory;

import java.time.Instant;
import java.util.List;

public record LowStockResult(int threshold, Instant generatedAt, List<LowStockAlert> alerts) { }
