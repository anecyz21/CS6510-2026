package edu.northeastern.cs6510.selfcheckout.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "self-checkout")
public record SelfCheckoutProperties(
        @Min(1) int catalogSize,
        @Min(0) int stockPerItem,
        @Min(0) int lowStockThreshold,
        @Min(1) int windowSize,
        @Min(1) int slideInterval) {

    public SelfCheckoutProperties {
        if (slideInterval > windowSize) {
            throw new IllegalArgumentException("slideInterval must be less than or equal to windowSize");
        }
    }
}
