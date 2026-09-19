package edu.northeastern.cs6510.selfcheckout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import edu.northeastern.cs6510.selfcheckout.config.SelfCheckoutProperties;

@SpringBootApplication
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class SelfCheckoutApplication {
    public static void main(String[] args) {
        SpringApplication.run(SelfCheckoutApplication.class, args);
    }
}
