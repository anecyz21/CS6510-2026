package edu.northeastern.cs6510.inventory;

import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InventoryApplication {
    public static void main(String[] args) {
        // Catalog restarts independently: never remember a failed lookup of its name.
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        Security.setProperty("networkaddress.cache.ttl", "5");
        SpringApplication.run(InventoryApplication.class, args);
    }
}
