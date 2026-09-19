package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.math.BigDecimal;

public record CatalogItemResponse(String sku, String name, BigDecimal price) { }
