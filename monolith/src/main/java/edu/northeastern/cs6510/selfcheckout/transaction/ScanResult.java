package edu.northeastern.cs6510.selfcheckout.transaction;

public record ScanResult(Transaction transaction, String sku, String name, long unitPriceCents) { }
