package edu.northeastern.cs6510.checkout.completion;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Marks the time spent inside a "local transaction", so fakes can detect remote calls made there. */
public class FlagTransactions implements TransactionOperations {
  private boolean inside;

  public boolean inside() { return inside; }

  @Override
  public <T> T execute(TransactionCallback<T> action) throws TransactionException {
    inside = true;
    try {
      return action.doInTransaction(new SimpleTransactionStatus());
    } finally {
      inside = false;
    }
  }
}
