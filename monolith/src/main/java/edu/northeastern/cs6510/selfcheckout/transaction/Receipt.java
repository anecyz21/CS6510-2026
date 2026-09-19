package edu.northeastern.cs6510.selfcheckout.transaction;


import edu.northeastern.cs6510.selfcheckout.transaction.ReceiptLine;

import java.time.Instant;
import java.util.List;

public record Receipt(String transactionId, String stationId, int itemCount, long totalAmountCents,
                      Instant startedAt, Instant completedAt, List<ReceiptLine> lines) { }
