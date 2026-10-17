package edu.northeastern.cs6510.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class CatalogApplication {
    public static void main(String[] args) { SpringApplication.run(CatalogApplication.class, args); }
    @RestController static class Readiness { private final JdbcTemplate db; Readiness(JdbcTemplate db){this.db=db;} @GetMapping("/actuator/health") String health() { db.queryForObject("SELECT 1", Integer.class); return "{\"status\":\"UP\"}"; } }
    /** Owns catalog validation and exposes only the contract-compatible read model. */
    @RestController static class Catalog { private final CatalogRepository repository; Catalog(CatalogRepository repository){this.repository=repository;}
        @GetMapping("/items") Map<String,Object> items(){ return Map.of("items", repository.activeItems().stream().map(i -> Map.of("sku",i.sku(),"name",i.name(),"price",i.priceCents()/100.0)).toList()); }
    }
}
