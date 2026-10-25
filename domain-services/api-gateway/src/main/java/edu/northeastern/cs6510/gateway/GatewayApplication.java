package edu.northeastern.cs6510.gateway;

import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The single public HTTP surface: routing, field checks and transport-error translation only. */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        // Owners restart and are recreated independently (US3): never remember a failed lookup, and
        // re-resolve service names often, so a returning owner is reachable at once at its current address.
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        Security.setProperty("networkaddress.cache.ttl", "5");
        SpringApplication.run(GatewayApplication.class, args);
    }
}
