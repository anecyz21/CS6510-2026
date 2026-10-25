package edu.northeastern.cs6510.checkout.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.contracts.inventory.v1.InternalErrorV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveRequestV1;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP implementation of {@link InventoryClient}: 300 ms connect and 1,500 ms read timeout (research R9). */
@Component
public class HttpInventoryClient implements InventoryClient {
  private final RestClient http;
  private final ObjectMapper json;

  public HttpInventoryClient(RestClient.Builder builder, ObjectMapper json, @Value("${inventory.url}") String inventoryUrl) {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(300))
        .version(HttpClient.Version.HTTP_1_1)
        .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofMillis(1500));
    this.http = builder.clone().baseUrl(inventoryUrl).requestFactory(factory).build();
    this.json = json;
  }

  @Override
  public Result reserve(String attemptId, ReserveRequestV1 request) {
    return send(() -> http.put().uri("/internal/v1/reservations/{id}", attemptId)
        .contentType(MediaType.APPLICATION_JSON).body(request), "reserve");
  }

  @Override
  public Result commit(String attemptId) {
    return send(() -> http.post().uri("/internal/v1/reservations/{id}/commit", attemptId), "commit");
  }

  @Override
  public Result cancel(String attemptId) {
    return send(() -> http.post().uri("/internal/v1/reservations/{id}/cancel", attemptId), "cancel");
  }

  @Override
  public Result get(String attemptId) {
    return send(() -> http.get().uri("/internal/v1/reservations/{id}", attemptId), "get");
  }

  private Result send(java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request, String command) {
    try {
      return request.get().exchange((req, res) -> {
        HttpStatusCode status = res.getStatusCode();
        byte[] body = res.getBody().readAllBytes();
        if (status.is2xxSuccessful()) return new Ok(json.readValue(body, ReservationV1.class));
        if (status.value() == 404) return new NotFound();
        if (status.value() == 409) {
          InternalErrorV1 error = json.readValue(body, InternalErrorV1.class);
          return new Conflict(error.error(), error.reservation());
        }
        if (status.is5xxServerError()) {
          throw new InventoryUnavailableException("inventory " + command + " returned " + status.value());
        }
        // A 400 means Checkout sent a malformed command: a bug, not an unknown outcome.
        throw new IllegalStateException("inventory " + command + " rejected the request: " + status.value() + " " + new String(body));
      });
    } catch (RestClientException e) {
      throw new InventoryUnavailableException("inventory " + command + ": " + e.getMessage(), e);
    } catch (CancellationException timedOut) {
      // Spring's JDK request factory enforces the read timeout by cancelling the request future.
      throw new InventoryUnavailableException("inventory " + command + ": read timed out", timedOut);
    } catch (InventoryUnavailableException | IllegalStateException e) {
      throw e;
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) throw new InventoryUnavailableException("inventory " + command + ": " + e.getMessage(), e);
      throw e;
    }
  }
}
