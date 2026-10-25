package edu.northeastern.cs6510.inventory.api;

/** The error body used by every public and internal endpoint: {"error": "...", "message": "..."}. */
public record ApiError(String error, String message) { }
