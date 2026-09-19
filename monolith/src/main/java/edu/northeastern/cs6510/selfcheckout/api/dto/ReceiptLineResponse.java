package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.math.BigDecimal;

public record ReceiptLineResponse(String sku, String name, BigDecimal unitPrice, int quantity) { }
