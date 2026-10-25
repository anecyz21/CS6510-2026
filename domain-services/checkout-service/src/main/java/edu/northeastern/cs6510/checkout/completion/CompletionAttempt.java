package edu.northeastern.cs6510.checkout.completion;

import java.time.Instant;
import java.util.List;

/** A completion attempt with its basket snapshot, as stored in Checkout's database. */
public record CompletionAttempt(
    String attemptId,
    String transactionId,
    AttemptState state,
    Instant createdAt,
    Instant updatedAt,
    Instant finalizedAt,
    int stepFailures,
    Instant nextRetryAt,
    boolean needsAttention,
    String lastError,
    List<AttemptLine> lines) { }
