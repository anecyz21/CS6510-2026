package edu.northeastern.cs6510.selfcheckout.catalog;

import java.util.Objects;

public record CatalogItem(String sku, String name, long priceCents) {
    public CatalogItem {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (priceCents < 0) {
            throw new IllegalArgumentException("priceCents must not be negative");
        }
        sku = sku.trim();
        name = name.trim();
    }
}
