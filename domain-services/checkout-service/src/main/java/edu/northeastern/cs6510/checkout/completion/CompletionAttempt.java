package edu.northeastern.cs6510.checkout.completion;

import java.time.Instant;
import java.util.List;

/**
 * A completion attempt with its basket snapshot, as stored in Checkout's database.
 * {@code commandSends} counts sends of the current state's command (1 after each transition);
 * {@code nextRetryAt} is when recovery resends it if no reply has moved the attempt on (005 data-model).
 */
public record CompletionAttempt(
    String attemptId,
    String transactionId,
    AttemptState state,
    Instant createdAt,
    Instant updatedAt,
    Instant finalizedAt,
    int commandSends,
    Instant nextRetryAt,
    boolean needsAttention,
    String lastError,
    List<AttemptLine> lines) { }
