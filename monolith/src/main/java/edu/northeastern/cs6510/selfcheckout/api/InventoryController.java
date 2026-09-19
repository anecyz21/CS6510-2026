package edu.northeastern.cs6510.selfcheckout.api;

import edu.northeastern.cs6510.selfcheckout.api.dto.*;
import edu.northeastern.cs6510.selfcheckout.inventory.LowStockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {
    private final LowStockService service;
    public InventoryController(LowStockService service) { this.service = service; }
    @GetMapping("/inventory/low-stock")
    public LowStockResponse lowStock(@RequestParam(required = false) Integer threshold) {
        var result = service.getLowStock(threshold);
        return new LowStockResponse(result.threshold(), result.generatedAt(), result.alerts().stream()
                .map(alert -> new LowStockAlertResponse(alert.sku(), alert.name(), alert.currentStock(), alert.threshold(), alert.triggeredAt())).toList());
    }
}
