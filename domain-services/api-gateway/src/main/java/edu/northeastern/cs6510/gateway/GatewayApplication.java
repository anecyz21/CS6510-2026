package edu.northeastern.cs6510.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
    @RestController static class Readiness { @GetMapping("/actuator/health") String health() { return "{\"status\":\"UP\"}"; } }
}
