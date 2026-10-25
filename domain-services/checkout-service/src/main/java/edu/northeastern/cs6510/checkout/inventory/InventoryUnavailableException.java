package edu.northeastern.cs6510.checkout.inventory;

/** Inventory did not confirm an outcome (transport error, timeout or 5xx): the outcome is unknown. */
public class InventoryUnavailableException extends RuntimeException {
  public InventoryUnavailableException(String message, Throwable cause) { super(message, cause); }
  public InventoryUnavailableException(String message) { super(message); }
}
