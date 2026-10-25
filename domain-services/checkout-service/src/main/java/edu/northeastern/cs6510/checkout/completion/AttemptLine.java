package edu.northeastern.cs6510.checkout.completion;

/**
 * One line of an attempt's immutable basket snapshot. {@code reservedQuantity} is null until the
 * prepare step stores Inventory's grant; for a FINALIZED attempt it is the purchased quantity.
 */
public record AttemptLine(String sku, int requestedQuantity, long unitPriceCents, String name, Integer reservedQuantity) { }
