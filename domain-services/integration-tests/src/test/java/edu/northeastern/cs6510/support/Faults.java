package edu.northeastern.cs6510.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;

/**
 * Fault injection for recovery tests (005 research R13). Needs the stack started with
 * {@code docker-compose.faults.yml} and tests run with {@code -Dstack.faults=true}. Services no
 * longer call each other, so there is no network hop to slow down: delays and lost replies are
 * made by pausing a container, holding Inventory's reply sends, or halting Checkout after a step.
 */
public final class Faults {
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private Faults() { }

  /** Skips the calling test unless the stack runs with the fault override. */
  public static void requireFaultsStack() {
    Assumptions.assumeTrue(Boolean.getBoolean("stack.faults"), "needs -Dstack.faults=true and docker-compose.faults.yml");
  }

  /** Halts Checkout's JVM right after the saga step {@code point} commits. */
  public static void haltAt(String point) { send("POST", point, "?action=HALT"); }

  public static void disarm(String point) { send("DELETE", point, ""); }

  /**
   * Pauses {@code service} (its consumers stop, and messages for it wait in Kafka) and unpauses it
   * after {@code duration} on a background thread. Closing the handle unpauses at once if needed.
   */
  public static AutoCloseable pauseFor(String service, Duration duration) {
    Stack.pause(service);
    AtomicBoolean resumed = new AtomicBoolean();
    Runnable resume = () -> { if (resumed.compareAndSet(false, true)) Stack.unpause(service); };
    Thread timer = new Thread(() -> {
      try {
        Thread.sleep(duration.toMillis());
        resume.run();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "unpause-" + service);
    timer.setDaemon(true);
    timer.start();
    return resume::run;
  }

  private static void send(String method, String point, String query) {
    URI uri = URI.create(Stack.SERVICE_URLS.get("checkout") + "/internal/v1/ops/faults/" + point + query);
    try {
      HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() == 404) fail("fault endpoint missing: start the stack with docker-compose.faults.yml");
      assertEquals(204, r.statusCode(), r.body());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
