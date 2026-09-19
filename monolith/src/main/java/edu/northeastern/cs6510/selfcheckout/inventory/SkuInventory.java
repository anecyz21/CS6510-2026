package edu.northeastern.cs6510.selfcheckout.inventory;

import java.time.Instant;

public record SkuInventory(String sku, int initialStock, int stock, int reserved, Instant lowStockSince) {
    public SkuInventory {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (initialStock < 0 || stock < 0 || reserved < 0 || reserved > stock) {
            throw new IllegalArgumentException("inventory invariant stock >= reserved >= 0 violated");
        }
    }
}
