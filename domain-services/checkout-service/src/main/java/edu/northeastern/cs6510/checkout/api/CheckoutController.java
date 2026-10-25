package edu.northeastern.cs6510.checkout.api;

import edu.northeastern.cs6510.checkout.CheckoutRepository;
import edu.northeastern.cs6510.checkout.CheckoutService;
import edu.northeastern.cs6510.checkout.scan.ScanService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Public Checkout routes, reached through the gateway. Money is exact cents, rendered with two decimals. */
@RestController
@RequestMapping("/transactions")
public class CheckoutController {
  private final CheckoutService service;
  private final ScanService scans;
  public CheckoutController(CheckoutService service, ScanService scans) { this.service = service; this.scans = scans; }
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> start(@RequestBody(required = false) Map<String,String> body) { return transaction(service.start(body == null ? null : body.get("stationId"))); }
  @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id) { return transaction(service.get(id)); }
  @PostMapping("/{id}/items") public Map<String,Object> scan(@PathVariable String id, @RequestBody(required = false) Map<String,String> body) {
    ScanService.ScanResult result=scans.scan(id, body == null ? null : body.get("sku"));
    return Map.of("transactionId",result.transactionId(),"sku",result.sku(),"name",result.name(),"unitPrice",money(result.unitPriceCents()),"itemCount",result.itemCount(),"runningTotal",money(result.runningTotalCents()));
  }
  @PostMapping("/{id}/complete") @ResponseStatus(HttpStatus.OK) public Map<String,Object> complete(@PathVariable String id) {
    service.complete(id);
    throw new IllegalStateException("unreachable: completion is unavailable until the saga lands");
  }
  private Map<String,Object> transaction(CheckoutRepository.Transaction t) { CheckoutRepository.Totals totals=service.basketTotals(t.transactionId()); return Map.of("transactionId",t.transactionId(),"stationId",t.stationId(),"status",t.status(),"itemCount",totals.itemCount(),"runningTotal",money(totals.cents()),"startedAt",t.startedAt().toString()); }
  private static BigDecimal money(long cents) { return BigDecimal.valueOf(cents, 2); }
}
