package edu.northeastern.cs6510.checkout.api;

import edu.northeastern.cs6510.checkout.CheckoutRepository;
import edu.northeastern.cs6510.checkout.CheckoutService;
import edu.northeastern.cs6510.checkout.completion.CompletionService;
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
  private final CompletionService completions;
  public CheckoutController(CheckoutService service, ScanService scans, CompletionService completions) { this.service = service; this.scans = scans; this.completions = completions; }
  /** A new transaction's basket is empty, so its totals are zero without reading them back. */
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> start(@RequestBody(required = false) Map<String,String> body) { return transaction(service.start(body == null ? null : body.get("stationId")), new CheckoutRepository.Totals(0, 0)); }
  @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id) { CheckoutRepository.Transaction t = service.get(id); return transaction(t, service.basketTotals(t.transactionId())); }
  @PostMapping("/{id}/items") public Map<String,Object> scan(@PathVariable String id, @RequestBody(required = false) Map<String,String> body) {
    ScanService.ScanResult result=scans.scan(id, body == null ? null : body.get("sku"));
    return Map.of("transactionId",result.transactionId(),"sku",result.sku(),"name",result.name(),"unitPrice",money(result.unitPriceCents()),"itemCount",result.itemCount(),"runningTotal",money(result.runningTotalCents()));
  }
  /** A 200 receipt covers only this call's purchases; unavailable units stay in the open basket (A1). */
  @PostMapping("/{id}/complete") @ResponseStatus(HttpStatus.OK) public Map<String,Object> complete(@PathVariable String id) { return completions.complete(id); }
  private Map<String,Object> transaction(CheckoutRepository.Transaction t, CheckoutRepository.Totals totals) { return Map.of("transactionId",t.transactionId(),"stationId",t.stationId(),"status",t.status(),"itemCount",totals.itemCount(),"runningTotal",money(totals.cents()),"startedAt",t.startedAt().toString()); }
  private static BigDecimal money(long cents) { return BigDecimal.valueOf(cents, 2); }
}
