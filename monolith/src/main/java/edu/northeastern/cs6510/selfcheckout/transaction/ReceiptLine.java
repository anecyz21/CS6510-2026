package edu.northeastern.cs6510.selfcheckout.transaction;

public record ReceiptLine(String sku, String name, long unitPriceCents, int quantity) { }
