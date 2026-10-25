import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Measures mean scan latency the way the load client sends it: one keep-alive JDK HttpClient
 * posting JSON bodies with BodyPublishers.ofString. Used for reports/redesign/GATEWAY-HOP.md.
 *
 * <pre>java domain-services/deployment/tools/HopProbe.java http://127.0.0.1:8080 200</pre>
 *
 * Inside a container (no compiler in the JRE images), compile it first with
 * {@code javac --release 21} and run {@code java -cp <dir> HopProbe <base-url> <scans>}.
 */
public class HopProbe {
  public static void main(String[] args) throws Exception {
    String base = args[0];
    int n = Integer.parseInt(args[1]);
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String tx = http.send(HttpRequest.newBuilder(URI.create(base + "/transactions"))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"stationId\":\"probe\"}")).build(),
        HttpResponse.BodyHandlers.ofString()).body().replaceAll(".*\"transactionId\":\"([^\"]+)\".*", "$1");
    long total = 0;
    for (int i = 0; i < n; i++) {
      long t0 = System.nanoTime();
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/transactions/" + tx + "/items"))
              .header("Content-Type", "application/json").header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"sku\":\"SKU-000001\"}")).build(),
          HttpResponse.BodyHandlers.ofString());
      total += System.nanoTime() - t0;
      if (r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + " " + r.body());
    }
    System.out.printf("%s: %d scans, mean %.2f ms%n", base, n, total / 1e6 / n);
  }
}
