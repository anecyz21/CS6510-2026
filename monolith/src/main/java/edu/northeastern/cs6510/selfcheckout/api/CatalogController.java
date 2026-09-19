package edu.northeastern.cs6510.selfcheckout.api;

import edu.northeastern.cs6510.selfcheckout.api.dto.*;
import edu.northeastern.cs6510.selfcheckout.catalog.CatalogService;
import static edu.northeastern.cs6510.selfcheckout.api.MoneySerialization.asDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }
    @GetMapping("/items")
    public CatalogResponse items() {
        return new CatalogResponse(catalog.findAll().stream().map(item -> new CatalogItemResponse(item.sku(), item.name(), asDecimal(item.priceCents()))).toList());
    }
}
