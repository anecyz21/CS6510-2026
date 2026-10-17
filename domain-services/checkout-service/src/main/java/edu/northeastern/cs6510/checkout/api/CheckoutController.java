package edu.northeastern.cs6510.checkout.api;

import edu.northeastern.cs6510.checkout.CheckoutRepository;
import edu.northeastern.cs6510.checkout.CheckoutService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class CheckoutController {
  private final CheckoutService service;
  public CheckoutController(CheckoutService service) { this.service = service; }
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> start(@RequestBody Map<String,String> body) { return transaction(service.start(body.get("stationId"))); }
  @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id) { return transaction(service.get(id)); }
  @PostMapping("/{id}/items") public Map<String,Object> scan(@PathVariable String id, @RequestBody Map<String,String> body) {
    CheckoutService.ScanResult result=service.acceptScan(id, body.get("sku")); CheckoutRepository.Totals totals=service.basketTotals(id);
    return Map.of("transactionId",result.transactionId(),"sku",result.sku(),"name",result.name(),"unitPrice",result.unitPriceCents()/100.0,"itemCount",totals.itemCount(),"runningTotal",totals.cents()/100.0);
  }
  @PostMapping("/{id}/complete") @ResponseStatus(HttpStatus.OK) public Map<String,Object> complete(@PathVariable String id) {
    service.complete(id); CheckoutRepository.Transaction transaction=service.get(id); CheckoutRepository.Totals totals=service.receiptTotals(id);
    return Map.of("transactionId",transaction.transactionId(),"stationId",transaction.stationId(),"itemCount",totals.itemCount(),"totalAmount",totals.cents()/100.0,"startedAt",transaction.startedAt().toString(),"completedAt",transaction.completedAt()==null?"":transaction.completedAt().toString(),"lines",java.util.List.of());
  }
  private Map<String,Object> transaction(CheckoutRepository.Transaction t) { CheckoutRepository.Totals totals=service.basketTotals(t.transactionId()); return Map.of("transactionId",t.transactionId(),"stationId",t.stationId(),"status",t.status(),"itemCount",totals.itemCount(),"runningTotal",totals.cents()/100.0,"startedAt",t.startedAt().toString()); }
}
