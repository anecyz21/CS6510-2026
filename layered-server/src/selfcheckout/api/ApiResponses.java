package selfcheckout.api;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ApiResponses {
    private ApiResponses() { }
    public static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    public static void error(HttpExchange exchange, int status, String error, String message) throws IOException {
        send(exchange, status, "{\"error\":" + Json.quote(error) + ",\"message\":" + Json.quote(message) + "}");
    }
}
