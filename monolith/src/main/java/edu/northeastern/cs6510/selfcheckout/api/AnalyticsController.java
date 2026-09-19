package edu.northeastern.cs6510.selfcheckout.api;
import edu.northeastern.cs6510.selfcheckout.analytics.*; import org.springframework.web.bind.annotation.*;
@RestController public class AnalyticsController {private final AnalyticsService service; public AnalyticsController(AnalyticsService service){this.service=service;} @GetMapping("/analytics/popular-items") public PopularityWindow items(@RequestParam(defaultValue="10") int limit){return service.current(limit);} }
