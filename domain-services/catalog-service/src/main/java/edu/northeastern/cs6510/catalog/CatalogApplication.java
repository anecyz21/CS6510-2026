package edu.northeastern.cs6510.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@SpringBootApplication
public class CatalogApplication {
    public static void main(String[] args) { SpringApplication.run(CatalogApplication.class, args); }
    /** Owns catalog validation and exposes only the contract-compatible read model. */
    @RestController static class Catalog { private final CatalogRepository repository; Catalog(CatalogRepository repository){this.repository=repository;}
        @GetMapping("/items") Map<String,Object> items(){ return Map.of("items", repository.activeItems().stream().map(i -> Map.of("sku",i.sku(),"name",i.name(),"price",i.priceCents()/100.0)).toList()); }
    }
}
