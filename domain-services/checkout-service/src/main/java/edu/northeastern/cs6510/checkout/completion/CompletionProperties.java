package edu.northeastern.cs6510.checkout.completion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Public completion settings (research R9). */
@ConfigurationProperties("checkout.completion")
public record CompletionProperties(Duration requestDeadline) {
  public CompletionProperties {
    if (requestDeadline == null) requestDeadline = Duration.ofSeconds(5);
  }

  public static CompletionProperties defaults() { return new CompletionProperties(null); }
}
