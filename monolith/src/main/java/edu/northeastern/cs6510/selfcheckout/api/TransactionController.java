package edu.northeastern.cs6510.selfcheckout.api;

import edu.northeastern.cs6510.selfcheckout.api.dto.*;
import edu.northeastern.cs6510.selfcheckout.transaction.*;
import static edu.northeastern.cs6510.selfcheckout.api.MoneySerialization.asDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class TransactionController {
    private final TransactionService service;
    public TransactionController(TransactionService service) { this.service = service; }
    @PostMapping("/transactions") @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse start(@RequestBody(required = false) StartTransactionRequest request) { return transactionResponse(service.start(request == null ? null : request.stationId())); }
    @PostMapping("/transactions/{transactionId}/items")
    public ScanResultResponse scan(@PathVariable String transactionId, @RequestBody(required = false) ScanItemRequest request) {
        ScanResult result = service.scan(transactionId, request == null ? null : request.sku());
        return new ScanResultResponse(result.transaction().transactionId(), result.sku(), result.name(), asDecimal(result.unitPriceCents()), result.transaction().itemCount(), asDecimal(result.transaction().runningTotalCents()));
    }
    @PostMapping("/transactions/{transactionId}/complete")
    public ReceiptResponse complete(@PathVariable String transactionId) {
        Receipt receipt = service.complete(transactionId);
        return new ReceiptResponse(receipt.transactionId(), receipt.stationId(), receipt.itemCount(), asDecimal(receipt.totalAmountCents()), receipt.startedAt(), receipt.completedAt(), receipt.lines().stream().map(line -> new ReceiptLineResponse(line.sku(), line.name(), asDecimal(line.unitPriceCents()), line.quantity())).toList());
    }
    @GetMapping("/transactions/{transactionId}")
    public TransactionResponse get(@PathVariable String transactionId) { return transactionResponse(service.get(transactionId)); }
    private TransactionResponse transactionResponse(Transaction transaction) { return new TransactionResponse(transaction.transactionId(), transaction.stationId(), transaction.status().name(), transaction.itemCount(), asDecimal(transaction.runningTotalCents()), transaction.startedAt()); }
}
