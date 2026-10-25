package edu.northeastern.cs6510.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fault injection for recovery tests. Needs the stack started with
 * {@code docker-compose.faults.yml} and tests run with {@code -Dstack.faults=true}.
 */
public final class Faults {
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private static final ToxiproxyClient TOXIPROXY = new ToxiproxyClient("127.0.0.1", 8474);

  private Faults() { }

  /** Halts Checkout's JVM the next time the saga reaches {@code point}. */
  public static void haltAt(String point) { arm(point, "HALT"); }

  /** Discards the Inventory response at {@code point} until {@link #disarm} (a lost response). */
  public static void loseResponseAt(String point) { arm(point, "LOSE"); }

  public static void disarm(String point) { send("DELETE", point, ""); }

  /** Delays every Inventory response on the Checkout-to-Inventory hop. */
  public static void inventoryLatency(long millis, long jitterMillis) {
    try {
      inventoryProxy().toxics().latency("latency", ToxicDirection.DOWNSTREAM, millis).setJitter(jitterMillis);
    } catch (IOException e) {
      throw new IllegalStateException("toxiproxy unavailable", e);
    }
  }

  /** Removes every toxic from the Inventory proxy. */
  public static void clearToxics() {
    try {
      for (Toxic toxic : inventoryProxy().toxics().getAll()) toxic.remove();
    } catch (IOException e) {
      throw new IllegalStateException("toxiproxy unavailable", e);
    }
  }

  private static Proxy inventoryProxy() throws IOException { return TOXIPROXY.getProxy("inventory"); }

  private static void arm(String point, String action) { send("POST", point, "?action=" + action); }

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
