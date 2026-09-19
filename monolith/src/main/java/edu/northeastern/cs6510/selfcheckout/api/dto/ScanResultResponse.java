package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.math.BigDecimal;

public record ScanResultResponse(String transactionId, String sku, String name, BigDecimal unitPrice,
                                 int itemCount, BigDecimal runningTotal) { }
