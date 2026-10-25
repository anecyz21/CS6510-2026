package edu.northeastern.cs6510.catalog.api;

/** The error body used by every public and internal endpoint: {"error": "...", "message": "..."}. */
public record ApiError(String error, String message) { }
