package edu.northeastern.cs6510.checkout;

import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class CheckoutApplication {
    public static void main(String[] args) {
        // Catalog and Inventory restart and are recreated independently: never remember a failed
        // lookup, and re-resolve their names often so a returning service is reached at once.
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        Security.setProperty("networkaddress.cache.ttl", "5");
        SpringApplication.run(CheckoutApplication.class, args);
    }
}
