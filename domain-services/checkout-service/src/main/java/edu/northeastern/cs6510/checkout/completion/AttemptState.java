package edu.northeastern.cs6510.checkout.completion;

/** Completion-attempt states (data-model "Completion attempt state machine"). */
public enum AttemptState {
  RESERVING, COMMITTING, CANCELLING, FINALIZED, REJECTED, CANCELLED;

  public boolean isTerminal() { return this == FINALIZED || this == REJECTED || this == CANCELLED; }
}
