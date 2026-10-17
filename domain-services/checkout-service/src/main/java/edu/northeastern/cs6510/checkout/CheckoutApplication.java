package edu.northeastern.cs6510.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class CheckoutApplication {
    public static void main(String[] args) { SpringApplication.run(CheckoutApplication.class, args); }
    @RestController static class Readiness { private final JdbcTemplate db; Readiness(JdbcTemplate db){this.db=db;} @GetMapping("/actuator/health") String health() { db.queryForObject("SELECT 1", Integer.class); return "{\"status\":\"UP\"}"; } }
}
