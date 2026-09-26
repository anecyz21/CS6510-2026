package selfcheckout.domain;

import java.time.Instant;
import java.util.List;

public record Receipt(String transactionId, String stationId, int itemCount, double totalAmount,
                      Instant startedAt, Instant completedAt, List<ReceiptLine> lines) { }
